package com.tarun.ledgerguard.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public response contract for account creation, per docs/API_SPEC.md.
 * Never exposes the JPA entity, and carries only the fields a public
 * caller is meant to see (no account category/class/purpose).
 */
public record AccountResponse(
		@Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID id,
		@Schema(example = "Ada Lovelace") String ownerName,
		@Schema(example = "USD") String currency,
		@Schema(type = "string", example = "0.0000", description = "Materialized account.balance") BigDecimal balance,
		Instant createdAt
) {
}
