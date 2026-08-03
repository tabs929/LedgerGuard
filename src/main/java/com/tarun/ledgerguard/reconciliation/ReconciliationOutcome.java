package com.tarun.ledgerguard.reconciliation;

/**
 * The complete, mutually exclusive set of Task 15 reconciliation
 * classifications. Exactly one applies to any given settlement
 * observation — {@link ReconciliationMatcher} stops at the first
 * applicable condition, so no result ever needs more than one code (see
 * docs/ARCHITECTURE.md's "Settlement Reconciliation" section for why a
 * single enum column is sufficient and a separate reason-code list is
 * not needed).
 *
 * <p>{@link #MATCHED} is a successful match. {@link #INTERNAL_LEDGER_INCONSISTENT}
 * is a data-integrity finding about LedgerGuard's own ledger data, not
 * about the external report. Every other value is an expected
 * reconciliation discrepancy — all of these are returned as ordinary
 * result data in a successful 2xx response, never as an HTTP-level
 * command failure.
 */
public enum ReconciliationOutcome {

	/** The reported transaction exists, is DEPOSIT-eligible, its ledger
	 * posting structure is valid, and both amount and currency agree. */
	MATCHED,

	/** No {@code ledger_transaction} row exists for the reported
	 * transaction id. Expected, valid evidence under Task 14's contract
	 * (an unmatched reference is retained, not rejected) — not a system
	 * error. */
	INTERNAL_TRANSACTION_NOT_FOUND,

	/** The reported transaction exists but its type is not
	 * settlement-eligible (only DEPOSIT is eligible in Task 15 — see
	 * {@link ReconciliationMatcher}). */
	INELIGIBLE_TRANSACTION_TYPE,

	/** Eligible and internally consistent; currency agrees, amount does not. */
	AMOUNT_MISMATCH,

	/** Eligible and internally consistent; amount agrees, currency does not. */
	CURRENCY_MISMATCH,

	/** Eligible and internally consistent; both amount and currency disagree. */
	AMOUNT_AND_CURRENCY_MISMATCH,

	/** The reported transaction is DEPOSIT-eligible but its own ledger
	 * posting structure fails validation (wrong entry count, wrong
	 * account taxonomy on either leg, unequal debit/credit amount or
	 * currency, or a non-positive amount) — a finding about LedgerGuard's
	 * own data, never about the external report. */
	INTERNAL_LEDGER_INCONSISTENT

}
