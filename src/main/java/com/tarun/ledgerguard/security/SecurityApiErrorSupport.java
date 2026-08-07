package com.tarun.ledgerguard.security;

import com.tarun.ledgerguard.common.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;

/**
 * Produces the exact same {@link ApiError} JSON envelope as
 * {@code common.GlobalExceptionHandler}, for use by the
 * {@code AuthenticationEntryPoint}/{@code AccessDeniedHandler} pair —
 * Spring Security's filter chain rejects a request before Spring MVC's
 * {@code @RestControllerAdvice} ever runs, so those two extension points
 * are the only place a 401/403 body can be written for a filter-chain
 * failure.
 */
@Component
public class SecurityApiErrorSupport {

	private final ObjectMapper objectMapper;

	public SecurityApiErrorSupport(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public void writeError(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
			String message) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		ApiError body = new ApiError(
				Instant.now().toString(),
				status.value(),
				status.getReasonPhrase(),
				message,
				request.getRequestURI());
		objectMapper.writeValue(response.getWriter(), body);
	}

}
