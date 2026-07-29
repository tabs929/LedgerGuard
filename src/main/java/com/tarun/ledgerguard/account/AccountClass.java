package com.tarun.ledgerguard.account;

/**
 * Mirrors the {@code account_class} (normal-balance side) values enforced by
 * {@code chk_account_class} in V1__init_account_ledger_schema.sql.
 */
public enum AccountClass {
	ASSET,
	LIABILITY
}
