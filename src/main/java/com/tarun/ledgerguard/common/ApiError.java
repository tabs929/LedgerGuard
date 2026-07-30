package com.tarun.ledgerguard.common;

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
public record ApiError(
		String timestamp,
		int status,
		String error,
		String message,
		String path
) {
}
