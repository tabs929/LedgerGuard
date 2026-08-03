package com.tarun.ledgerguard.reconciliation;

import java.time.Instant;
import java.util.UUID;

/**
 * A persisted {@code reconciliation_run} row, exactly as committed —
 * including the database-assigned {@code createdAt}, always read back
 * from the {@code RETURNING} clause of the insert that created it, never
 * computed client-side.
 */
record StoredReconciliationRun(
		UUID id,
		UUID settlementImportId,
		int algorithmVersion,
		int totalResultCount,
		int matchedCount,
		int discrepancyCount,
		int inconsistentCount,
		Instant createdAt) {
}
