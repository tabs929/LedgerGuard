package com.tarun.ledgerguard.ledger;

/**
 * Mirrors the {@code transaction_type} values enforced by
 * {@code chk_transaction_type} in V1__init_account_ledger_schema.sql.
 */
public enum TransactionType {
	DEPOSIT,
	TRANSFER
}
