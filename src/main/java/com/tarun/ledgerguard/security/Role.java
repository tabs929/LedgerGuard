package com.tarun.ledgerguard.security;

/**
 * The only two authorities LedgerGuard recognizes (Task 17). Sourced
 * exclusively from server-side configuration ({@code
 * ledgerguard.security.users[].role}) — never chosen or overridden by a
 * token request or any other client input.
 */
public enum Role {
	CUSTOMER,
	OPERATIONS
}
