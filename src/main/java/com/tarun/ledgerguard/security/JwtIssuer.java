package com.tarun.ledgerguard.security;

import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Issues short-lived, signed HS256 access tokens for
 * {@code POST /api/v1/auth/token}. The {@code sub} claim is the
 * configured username itself (stable, never regenerated); {@code roles}
 * carries exactly the one server-configured role — never anything the
 * token request supplied.
 */
@Component
public class JwtIssuer {

	private final JwtEncoder jwtEncoder;
	private final LedgerGuardSecurityProperties properties;

	public JwtIssuer(JwtEncoder jwtEncoder, LedgerGuardSecurityProperties properties) {
		this.jwtEncoder = jwtEncoder;
		this.properties = properties;
	}

	public String issue(String username, Role role) {
		LedgerGuardSecurityProperties.Jwt jwtProperties = properties.getJwt();
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.issuer(jwtProperties.getIssuer())
				.audience(List.of(jwtProperties.getAudience()))
				.issuedAt(now)
				.expiresAt(now.plusSeconds(jwtProperties.getExpirationSeconds()))
				.subject(username)
				.claim("roles", List.of(role.name()))
				.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

}
