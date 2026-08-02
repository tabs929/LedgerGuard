package com.tarun.ledgerguard.settlement;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.UUID;

/**
 * Public response contract for POST /api/v1/settlement-imports, per
 * docs/API_SPEC.md. Never includes raw CSV content -- only aggregate
 * counts and the committed import's identity.
 */
@Schema(description = "Result of a settlement CSV import: either a newly recorded import (201) or an "
		+ "exact-file replay of an already-committed import (200, replayed=true).")
public record SettlementImportResponse(
		@Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID importId,
		@Schema(example = "acme-bank", description = "The submitted display value of source") String source,
		@Schema(example = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde",
				description = "Lowercase SHA-256 hex digest of the exact uploaded file bytes") String fileHash,
		@Schema(example = "10") int totalRows,
		@Schema(example = "8") int insertedRows,
		@Schema(example = "2") int duplicateRows,
		@Schema(example = "false", description = "True if this response is a replay of an already-committed import")
		boolean replayed,
		@Schema(example = "2026-08-02T10:00:00Z") Instant importedAt) {

	static SettlementImportResponse from(SettlementImportOutcome outcome) {
		StoredSettlementImport stored = outcome.storedImport();
		return new SettlementImportResponse(
				stored.id(),
				stored.source(),
				stored.fileHash(),
				stored.totalRowCount(),
				stored.insertedRowCount(),
				stored.duplicateRowCount(),
				outcome.replayed(),
				stored.importedAt());
	}

}
