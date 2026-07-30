package com.tarun.ledgerguard.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public response contract for GET /api/v1/accounts/{id}/balance, per
 * docs/API_SPEC.md. {@code balance} is the persisted materialized
 * {@code account.balance} column, read directly — never recomputed from
 * the ledger and never cached in application memory.
 */
public record AccountBalanceResponse(
		@Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID accountId,
		@Schema(type = "string", example = "150.0000", description = "Materialized account.balance") BigDecimal balance,
		@Schema(example = "USD") String currency
) {
}
