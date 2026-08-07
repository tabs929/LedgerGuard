package com.tarun.ledgerguard.security;

import org.springframework.security.access.AccessDeniedException;

/**
 * Shared service-layer role enforcement. Thrown as a plain Spring Security
 * {@link AccessDeniedException} so it is caught by the same
 * {@code ExceptionTranslationFilter}/{@code ApiAccessDeniedHandler} pair
 * that handles a filter-chain-level denial, producing the identical 403
 * {@code ApiError} body regardless of which layer detected the violation.
 *
 * <p>This is the second, mandatory layer of defense described in
 * docs/ARCHITECTURE.md: {@code SecurityConfig}'s URL rules are the coarse
 * first layer, but every service method listed there re-checks the role
 * (and, where applicable, ownership) itself, so a hypothetical caller
 * invoking a service directly cannot bypass authorization.
 */
public final class AuthorizationSupport {

	private AuthorizationSupport() {
	}

	public static void requireRole(AuthenticatedPrincipal principal, Role required) {
		if (principal.role() != required) {
			throw new AccessDeniedException("This operation requires the " + required + " role.");
		}
	}

}
