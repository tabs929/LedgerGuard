package com.tarun.ledgerguard.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public response contract for a successful deposit, per docs/API_SPEC.md.
 * Never exposes the JPA entities (Account, LedgerTransaction, LedgerEntry).
 */
public record DepositResponse(
		@Schema(example = "b2f1d3d0-4a3e-4b2e-8b1e-2a7e6c1a9f10", description = "The id of the transaction created by this deposit")
		UUID transactionId,
		@Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID accountId,
		@Schema(type = "string", example = "100.00") BigDecimal amount,
		@Schema(example = "USD") String currency,
		@Schema(type = "string", example = "100.0000", description = "Materialized account.balance after the deposit")
		BigDecimal newBalance,
		Instant createdAt
) {
}
