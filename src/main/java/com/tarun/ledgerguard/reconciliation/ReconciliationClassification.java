package com.tarun.ledgerguard.reconciliation;

import java.math.BigDecimal;

/**
 * {@link ReconciliationMatcher}'s pure output for one settlement
 * observation: the outcome, plus the internal amount/currency it was
 * compared against when one was actually validated and trustworthy
 * ({@code null} for both fields for {@link ReconciliationOutcome#INTERNAL_TRANSACTION_NOT_FOUND},
 * {@link ReconciliationOutcome#INELIGIBLE_TRANSACTION_TYPE}, and
 * {@link ReconciliationOutcome#INTERNAL_LEDGER_INCONSISTENT} — populated
 * together for every other outcome).
 */
record ReconciliationClassification(ReconciliationOutcome outcome, BigDecimal internalAmount, String internalCurrency) {
}
