package com.tarun.ledgerguard.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.util.Base64;

/**
 * Stateless JWT resource-server configuration for Task 17. Two layers of
 * defense are documented across this class and the service layer: this
 * {@code SecurityFilterChain} is the coarse, URL/method/role-based first
 * layer; every service method listed in docs/ARCHITECTURE.md independently
 * re-verifies ownership, so bypassing the controller cannot bypass
 * authorization.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(LedgerGuardSecurityProperties.class)
public class SecurityConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	@Bean
	public SecretKeySpec jwtSigningKey(LedgerGuardSecurityProperties properties) {
		byte[] keyBytes = Base64.getDecoder().decode(properties.getJwt().getSigningKey());
		return new SecretKeySpec(keyBytes, "HmacSHA256");
	}

	@Bean
	public JwtEncoder jwtEncoder(SecretKeySpec jwtSigningKey) {
		OctetSequenceKey jwk = new OctetSequenceKey.Builder(jwtSigningKey)
				.algorithm(com.nimbusds.jose.JWSAlgorithm.HS256)
				.build();
		JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(jwk));
		return new NimbusJwtEncoder(jwkSource);
	}

	@Bean
	public JwtDecoder jwtDecoder(SecretKeySpec jwtSigningKey, LedgerGuardSecurityProperties properties) {
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
				.macAlgorithm(MacAlgorithm.HS256)
				.build();

		LedgerGuardSecurityProperties.Jwt jwtProperties = properties.getJwt();
		OAuth2TokenValidator<Jwt> timestampValidator =
				new JwtTimestampValidator(Duration.ofSeconds(jwtProperties.getClockSkewSeconds()));
		OAuth2TokenValidator<Jwt> issuerValidator = new JwtIssuerValidator(jwtProperties.getIssuer());
		OAuth2TokenValidator<Jwt> audienceValidator = token -> {
			if (token.getAudience() != null && token.getAudience().contains(jwtProperties.getAudience())) {
				return OAuth2TokenValidatorResult.success();
			}
			return OAuth2TokenValidatorResult.failure(
					new OAuth2Error("invalid_token", "The required audience is missing.", null));
		};

		decoder.setJwtValidator(
				new DelegatingOAuth2TokenValidator<>(timestampValidator, issuerValidator, audienceValidator));
		return decoder;
	}

	// Reads authorities from the "roles" claim only, with no prefix --
	// deliberately not Spring's default "scope"/"scp" claim and "SCOPE_"
	// prefix, since this application's tokens carry LedgerGuard's own
	// CUSTOMER/OPERATIONS authorities, not OAuth2 scopes.
	@Bean
	public JwtAuthenticationConverter jwtAuthenticationConverter() {
		JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
		authoritiesConverter.setAuthoritiesClaimName("roles");
		authoritiesConverter.setAuthorityPrefix("");

		JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
		converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
		return converter;
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http,
			JwtAuthenticationConverter jwtAuthenticationConverter,
			JwtAuthenticationEntryPoint authenticationEntryPoint,
			ApiAccessDeniedHandler accessDeniedHandler) throws Exception {
		http
				// Bearer-token API, no cookie-based session -- there is no CSRF
				// risk to defend against, and no session to fixate.
				.csrf(csrf -> csrf.disable())
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(auth -> auth
						.requestMatchers(HttpMethod.POST, "/api/v1/auth/token").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/v1/accounts").hasAuthority("CUSTOMER")
						.requestMatchers(HttpMethod.POST, "/api/v1/accounts/*/deposits").hasAuthority("CUSTOMER")
						.requestMatchers(HttpMethod.POST, "/api/v1/transfers").hasAuthority("CUSTOMER")
						.requestMatchers(HttpMethod.GET, "/api/v1/accounts/*/balance")
						.hasAnyAuthority("CUSTOMER", "OPERATIONS")
						.requestMatchers(HttpMethod.GET, "/api/v1/accounts/*/transactions")
						.hasAnyAuthority("CUSTOMER", "OPERATIONS")
						.requestMatchers("/api/v1/settlement-imports/**").hasAuthority("OPERATIONS")
						.requestMatchers("/api/v1/**").authenticated()
						.requestMatchers("/actuator/health/**").permitAll()
						.requestMatchers("/actuator/**").hasAuthority("OPERATIONS")
						.requestMatchers("/", "/index.html", "/styles.css", "/app.js").permitAll()
						.requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
					.permitAll()
						// Genuinely unmapped paths fall through unauthenticated to
						// Spring MVC's own NoResourceFoundException handling
						// (common.GlobalExceptionHandler), which returns 404 -- not
						// intercepted here as a 401/403/500.
						.anyRequest().permitAll())
				.oauth2ResourceServer(oauth2 -> oauth2
						.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)))
				.exceptionHandling(exceptions -> exceptions
						.authenticationEntryPoint(authenticationEntryPoint)
						.accessDeniedHandler(accessDeniedHandler));

		return http.build();
	}

}
