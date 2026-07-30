package com.tarun.ledgerguard.account.dto;

import com.tarun.ledgerguard.ledger.LedgerEntryType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One item in GET /api/v1/accounts/{id}/transactions, per docs/API_SPEC.md.
 * Represents exactly one ledger_entry row belonging to the requested
 * account. {@code transactionId} is the entry's owning ledger_transaction
 * id, not the entry's own id (the entry's own id is used only as an
 * internal query tie-breaker, per the approved ordering contract, and is
 * not itself part of this response shape). No counterparty fields — none
 * are required by the approved contract.
 */
public record TransactionHistoryItem(
		UUID transactionId,
		LedgerEntryType entryType,
		BigDecimal amount,
		String currency,
		Instant createdAt
) {
}
