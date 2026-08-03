package com.tarun.ledgerguard.reconciliation;

import java.time.Instant;
import java.util.UUID;

/**
 * The subset of one {@code settlement_import} row's fields Task 15 needs —
 * existence, plus the row counts that make its response summary's
 * {@code importedFileRows}/{@code newlyRecordedObservations}/
 * {@code duplicateRows} fields unambiguous (see
 * {@code SettlementImportSummaryRepository}). Read directly from the
 * database rather than through the {@code settlement} package's own
 * (deliberately package-private) types, so Task 15 depends on nothing
 * from Task 14's internals and cannot accidentally affect its behavior.
 */
record SettlementImportSummary(
		UUID id,
		int totalRowCount,
		int insertedRowCount,
		int duplicateRowCount,
		Instant importedAt) {
}
