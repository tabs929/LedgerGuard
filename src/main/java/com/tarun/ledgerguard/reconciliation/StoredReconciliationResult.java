package com.tarun.ledgerguard.reconciliation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A persisted {@code reconciliation_result} row.
 */
record StoredReconciliationResult(
		UUID id,
		UUID runId,
		UUID settlementRecordId,
		UUID reportedTransactionId,
		ReconciliationOutcome outcome,
		BigDecimal reportedAmount,
		String reportedCurrency,
		BigDecimal internalAmount,
		String internalCurrency,
		Instant createdAt) {
}
