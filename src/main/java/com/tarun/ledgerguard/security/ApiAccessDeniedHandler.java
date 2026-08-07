package com.tarun.ledgerguard.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * A validly authenticated principal whose role does not permit the
 * requested capability (a blanket, non-resource-specific denial — e.g.
 * CUSTOMER calling a settlement/reconciliation endpoint). Resource-specific
 * ownership violations are deliberately NOT handled here — they are
 * reported as 404 by the owning service, per the project's existing
 * SYSTEM-account-as-404 precedent, so this handler only ever produces a
 * blanket capability-denial 403.
 */
@Component
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

	private final SecurityApiErrorSupport errorSupport;

	public ApiAccessDeniedHandler(SecurityApiErrorSupport errorSupport) {
		this.errorSupport = errorSupport;
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response,
			AccessDeniedException accessDeniedException) throws IOException {
		errorSupport.writeError(request, response, HttpStatus.FORBIDDEN, "Access to this resource is denied.");
	}

}
