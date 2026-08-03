package com.tarun.ledgerguard.reconciliation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Public response contract for the reconciliation command and summary
 * endpoints, per docs/API_SPEC.md. {@code importedFileRows}/
 * {@code newlyRecordedObservations}/{@code duplicateRows} are Task 14's
 * own {@code settlement_import} counts (total/inserted/duplicate row
 * counts), included here specifically so a caller can see, unambiguously,
 * that {@code reconciliationResultCount} reconciles only the observations
 * this import first recorded — not necessarily every row its uploaded
 * file contained (see {@code SettlementObservationRepository}'s Javadoc).
 * {@code reconciliationResultCount} may legitimately be zero (an
 * all-duplicate import).
 */
@Schema(description = "Summary of one settlement import's reconciliation run.")
public record ReconciliationSummaryResponse(
		@Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID runId,
		@Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID settlementImportId,
		@Schema(example = "1") int algorithmVersion,
		@Schema(example = "10", description = "settlement_import.total_row_count -- every row the uploaded file contained")
		int importedFileRows,
		@Schema(example = "8", description = "settlement_import.inserted_row_count -- observations first recorded by this import")
		int newlyRecordedObservations,
		@Schema(example = "2", description = "settlement_import.duplicate_row_count") int duplicateRows,
		@Schema(example = "8", description = "Number of reconciliation_result rows this run produced -- "
				+ "equal to newlyRecordedObservations by construction; may be zero for an all-duplicate import")
		int reconciliationResultCount,
		@Schema(example = "6") int matchedCount,
		@Schema(example = "1") int discrepancyCount,
		@Schema(example = "1") int inconsistentCount,
		@Schema(example = "false", description = "True if this response is a replay of an already-committed run")
		boolean replayed,
		@Schema(example = "2026-08-03T10:00:00Z") Instant createdAt) {
}
