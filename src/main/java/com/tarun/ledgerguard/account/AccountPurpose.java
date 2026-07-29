package com.tarun.ledgerguard.account;

/**
 * Mirrors the {@code account_purpose} values enforced by
 * {@code chk_account_purpose} in V1__init_account_ledger_schema.sql.
 */
public enum AccountPurpose {
	CUSTOMER_WALLET,
	EXTERNAL_FUNDING
}
