package com.tarun.ledgerguard.account;

/**
 * Thrown when a request supplies a well-formed but unsupported currency
 * code (Phase 1 supports USD only). Handled locally by AccountController
 * and mapped to 422, per docs/API_SPEC.md. This is intentionally a small,
 * endpoint-local exception rather than part of a global exception
 * hierarchy — the full cross-cutting error-handling framework is Task 7's
 * responsibility.
 */
public class UnsupportedCurrencyException extends RuntimeException {

	public UnsupportedCurrencyException(String currency) {
		super("Unsupported currency: " + currency + " (Phase 1 supports USD only)");
	}

}
