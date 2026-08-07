package com.tarun.ledgerguard.security;

import com.tarun.ledgerguard.security.dto.TokenRequest;
import com.tarun.ledgerguard.security.dto.TokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * The only public authentication endpoint (Task 17). Issues a short-lived
 * HS256 JWT for one of the fixed, configuration-backed demo identities.
 * Never distinguishes "no such user" from "wrong password" in its
 * response, status code, or logs.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Stateless JWT issuance for the fixed CUSTOMER/OPERATIONS demo identities.")
public class TokenController {

	// A syntactically valid BCrypt hash with no corresponding real
	// credential, used only so that a lookup for a nonexistent username
	// still runs a full BCrypt comparison -- this keeps the response time
	// for "unknown user" and "wrong password" from trivially differing in
	// a way that would disclose which case occurred.
	private static final String DUMMY_HASH = "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final LedgerGuardSecurityProperties properties;
	private final PasswordEncoder passwordEncoder;
	private final JwtIssuer jwtIssuer;

	public TokenController(LedgerGuardSecurityProperties properties, PasswordEncoder passwordEncoder,
			JwtIssuer jwtIssuer) {
		this.properties = properties;
		this.passwordEncoder = passwordEncoder;
		this.jwtIssuer = jwtIssuer;
	}

	@PostMapping("/token")
	// Overrides OpenApiConfig's global bearerAuth requirement -- this is
	// the one endpoint that must be documented as NOT requiring a token,
	// since it is what issues one.
	@SecurityRequirements
	@Operation(summary = "Obtain a bearer access token",
			description = "Authenticates one of the fixed, configuration-backed demo identities and returns "
					+ "a short-lived signed JWT. The role is always the one assigned to the identity in "
					+ "server configuration -- there is no request field to choose or override it.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Token issued",
					content = @Content(schema = @Schema(implementation = TokenResponse.class))),
			@ApiResponse(responseCode = "401", description = "Unknown username or incorrect password -- the "
					+ "response is identical either way")
	})
	public TokenResponse issueToken(@Valid @RequestBody TokenRequest request) {
		Optional<LedgerGuardSecurityProperties.UserConfig> user = properties.getUsers().stream()
				.filter(candidate -> candidate.getUsername().equals(request.username()))
				.findFirst();

		String hashToCheck = user.map(LedgerGuardSecurityProperties.UserConfig::getPasswordHash).orElse(DUMMY_HASH);
		boolean passwordMatches = passwordEncoder.matches(request.password(), hashToCheck);

		if (user.isEmpty() || !passwordMatches) {
			throw new InvalidCredentialsException();
		}

		LedgerGuardSecurityProperties.UserConfig authenticatedUser = user.get();
		String token = jwtIssuer.issue(authenticatedUser.getUsername(), authenticatedUser.getRole());
		return new TokenResponse(token, "Bearer", properties.getJwt().getExpirationSeconds());
	}

}
