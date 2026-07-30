package com.tarun.ledgerguard.ledger;

/**
 * Mirrors the {@code entry_type} values enforced by
 * {@code chk_ledger_entry_type} in V1__init_account_ledger_schema.sql.
 */
public enum LedgerEntryType {
	DEBIT,
	CREDIT
}
