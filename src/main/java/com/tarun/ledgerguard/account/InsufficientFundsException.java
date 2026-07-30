package com.tarun.ledgerguard.account;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Thrown when a transfer's source account balance is less than the
 * requested transfer amount. Per docs/API_SPEC.md, mapped to 422 — never
 * silently converted into a generic error, and never allowed to reach the
 * database as a negative-balance write (the check happens before any
 * ledger or balance mutation).
 */
public class InsufficientFundsException extends RuntimeException {

	public InsufficientFundsException(UUID sourceAccountId, BigDecimal available, BigDecimal requested) {
		super("Insufficient funds in account " + sourceAccountId + ": available " + available
				+ ", requested " + requested);
	}

}
