package com.tarun.ledgerguard.reconciliation;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The subset of one {@code settlement_record} row's fields Task 15
 * actually needs for matching and result persistence — read directly from
 * the database by {@code SettlementObservationRepository}, never through
 * the {@code settlement} package's own (deliberately package-private)
 * types, since Task 15 must not depend on or modify Task 14's internals.
 */
record SettlementObservation(
		UUID settlementRecordId,
		UUID reportedTransactionId,
		BigDecimal reportedAmount,
		String reportedCurrency,
		int sourceRowNumber) {
}
