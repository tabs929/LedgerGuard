package com.tarun.ledgerguard.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public response contract for a successful transfer, per
 * docs/API_SPEC.md. Deliberately has no balance field — unlike deposits,
 * the documented transfer response does not include a resulting balance.
 * Never exposes the JPA entities (Account, LedgerTransaction, LedgerEntry).
 */
public record TransferResponse(
		@Schema(example = "b2f1d3d0-4a3e-4b2e-8b1e-2a7e6c1a9f10", description = "The id of the transaction created by this transfer")
		UUID transactionId,
		@Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID sourceAccountId,
		@Schema(example = "9c858901-8a57-4791-81fe-4c455b099bc9") UUID destinationAccountId,
		@Schema(type = "string", example = "50.00") BigDecimal amount,
		@Schema(example = "USD") String currency,
		Instant createdAt
) {
}
