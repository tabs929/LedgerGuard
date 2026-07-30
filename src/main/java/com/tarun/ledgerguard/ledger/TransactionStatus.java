package com.tarun.ledgerguard.ledger;

/**
 * Mirrors the {@code status} values enforced by {@code chk_transaction_status}
 * in V1__init_account_ledger_schema.sql. Phase 1 has no partial/pending
 * transaction states — only terminal-success rows are ever written.
 */
public enum TransactionStatus {
	COMPLETED
}
