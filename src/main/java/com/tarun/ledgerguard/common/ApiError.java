package com.tarun.ledgerguard.common;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The shared error-response envelope for every public endpoint, per
 * docs/API_SPEC.md's "Error Response Shape". Exactly these five fields —
 * no error code, no structured field-error list, no correlation id, since
 * none of those are part of the approved contract. {@code timestamp} is an
 * ISO-8601 instant string; {@code status}/{@code error} mirror the HTTP
 * status code and reason phrase; {@code message} is always present (never
 * omitted the way Spring Boot's own default error body omits it) and never
 * contains a Java class name, stack trace, SQL, or constraint name.
 */
@Schema(description = "Shared error envelope returned by every endpoint. Never contains internal "
		+ "implementation details (stack traces, SQL, constraint names, Java class names).")
public record ApiError(
		@Schema(example = "2026-07-30T19:20:00.123456Z") String timestamp,
		@Schema(example = "404") int status,
		@Schema(example = "Not Found") String error,
		@Schema(example = "Account not found: 3fa85f64-5717-4562-b3fc-2c963f66afa6") String message,
		@Schema(example = "/api/v1/accounts/3fa85f64-5717-4562-b3fc-2c963f66afa6/balance") String path
) {
}
