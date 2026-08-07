package com.tarun.ledgerguard.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Missing, malformed, invalid, or expired bearer token — never reveals the
 * underlying JWT validation failure (signature, issuer, audience,
 * expiration) to the client.
 */
@Component
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

	private final SecurityApiErrorSupport errorSupport;

	public JwtAuthenticationEntryPoint(SecurityApiErrorSupport errorSupport) {
		this.errorSupport = errorSupport;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException authException) throws IOException {
		errorSupport.writeError(request, response, HttpStatus.UNAUTHORIZED,
				"Full authentication is required to access this resource.");
	}

}
