package com.tarun.ledgerguard.settlement;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One syntactically and semantically validated CSV data row, produced by
 * {@link SettlementCsvParser}. Immutable, contains no database access.
 * {@code sourceRowNumber} is 1-based and counts data rows only (the header
 * row is never counted), matching the "logical CSV row number" the Task
 * 14 error contract requires. {@code rowHash} is the canonical fingerprint
 * from {@link RowFingerprint}, computed once here so both duplicate
 * classification and persistence reuse the exact same value.
 */
record SettlementCsvRow(
		int sourceRowNumber,
		String externalReference,
		UUID transactionId,
		BigDecimal amount,
		String currency,
		Instant settledAt,
		String rowHash) {
}
