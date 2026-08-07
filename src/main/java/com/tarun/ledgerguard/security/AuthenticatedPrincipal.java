package com.tarun.ledgerguard.security;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * The explicit identity passed into every service-layer method that
 * enforces ownership or role-based authorization (Task 17). Threaded from
 * controller to service as an ordinary method parameter — never read from
 * a thread-local or the Spring Security context inside a service — so that
 * a hypothetical caller invoking a service directly (bypassing the
 * controller and the {@code SecurityFilterChain}) is still required to
 * supply a principal and is still subject to the same check.
 */
public record AuthenticatedPrincipal(String subject, Role role) {

	public static AuthenticatedPrincipal fromJwt(Jwt jwt) {
		String subject = jwt.getSubject();
		List<String> roles = jwt.getClaimAsStringList("roles");
		if (roles == null || roles.isEmpty()) {
			throw new IllegalStateException("JWT is missing the required 'roles' claim");
		}
		return new AuthenticatedPrincipal(subject, Role.valueOf(roles.get(0)));
	}

}
