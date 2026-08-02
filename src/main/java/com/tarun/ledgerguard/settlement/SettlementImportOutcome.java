package com.tarun.ledgerguard.settlement;

/**
 * The result of one call to {@code SettlementImportProcessor.importFile}:
 * the committed {@code settlement_import} row, and whether it is a fresh
 * import (201) or an exact-file replay of an already-committed import
 * (200).
 */
record SettlementImportOutcome(StoredSettlementImport storedImport, boolean replayed) {
}
