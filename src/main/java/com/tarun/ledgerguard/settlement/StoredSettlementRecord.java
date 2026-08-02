package com.tarun.ledgerguard.settlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A persisted {@code settlement_record} row. {@code transactionId} is
 * stored and returned exactly as reported by the external source, with no
 * relationship enforced or assumed to an actual {@code ledger_transaction}
 * row -- see V5's header comment.
 */
record StoredSettlementRecord(
		UUID id,
		String normalizedSource,
		String externalReference,
		UUID transactionId,
		BigDecimal amount,
		String currency,
		Instant settledAt,
		String rowHash,
		UUID firstImportId,
		int sourceRowNumber,
		Instant createdAt) {
}
