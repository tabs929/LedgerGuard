package com.tarun.ledgerguard.settlement;

import java.time.Instant;
import java.util.UUID;

/**
 * A persisted {@code settlement_import} row, exactly as committed --
 * including the database-assigned {@code importedAt}, always read back
 * from the {@code RETURNING} clause of the insert that created it (see
 * {@link SettlementImportRepository#tryInsert}), never computed
 * client-side.
 */
record StoredSettlementImport(
		UUID id,
		String source,
		String normalizedSource,
		String originalFilename,
		String fileHash,
		long fileSizeBytes,
		int totalRowCount,
		int insertedRowCount,
		int duplicateRowCount,
		Instant importedAt) {
}
