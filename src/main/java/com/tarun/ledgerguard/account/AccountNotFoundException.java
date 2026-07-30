package com.tarun.ledgerguard.account;

import java.util.UUID;

/**
 * Thrown when an account id does not resolve to a usable public account —
 * either because no row exists, or because the row is a SYSTEM account.
 * Per docs/API_SPEC.md, a SYSTEM account id is deliberately treated
 * identically to a nonexistent id (both 404), so this endpoint never
 * discloses whether a given id belongs to a restricted internal account.
 */
public class AccountNotFoundException extends RuntimeException {

	public AccountNotFoundException(UUID accountId) {
		super("Account not found: " + accountId);
	}

}
