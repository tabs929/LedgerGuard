package com.tarun.ledgerguard.account;

import java.util.UUID;

/**
 * Thrown when a request's (already-USD-validated) currency does not match
 * the destination account's own currency. In Phase 1 every account is
 * expected to be USD, but the database schema does not enforce that (see
 * V1__init_account_ledger_schema.sql's header comment) — this exception is
 * what catches a non-USD account row if one is ever created directly,
 * bypassing account creation's application-level USD-only validation.
 */
public class CurrencyMismatchException extends RuntimeException {

	public CurrencyMismatchException(UUID accountId, String accountCurrency, String requestCurrency) {
		super("Currency mismatch for account " + accountId + ": account is " + accountCurrency
				+ ", request is " + requestCurrency);
	}

}
