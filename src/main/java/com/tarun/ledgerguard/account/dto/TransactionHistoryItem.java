package com.tarun.ledgerguard.account.dto;

import com.tarun.ledgerguard.ledger.LedgerEntryType;
import io.swagger.v3.oas.annotations.media.Schema;

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
		@Schema(example = "b2f1d3d0-4a3e-4b2e-8b1e-2a7e6c1a9f10", description = "The id of the transaction this entry belongs to")
		UUID transactionId,
		@Schema(description = "DEBIT if this wallet sent funds (a transfer out); "
				+ "CREDIT if this wallet received funds (a deposit, or a transfer in)")
		LedgerEntryType entryType,
		@Schema(type = "string", example = "25.00") BigDecimal amount,
		@Schema(example = "USD") String currency,
		Instant createdAt
) {
}
