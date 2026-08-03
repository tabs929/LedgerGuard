package com.tarun.ledgerguard.reconciliation;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public per-observation response contract, per docs/API_SPEC.md.
 * {@code internalAmount}/{@code internalCurrency} are {@code null}
 * together when no trustworthy internal value exists ({@code outcome} is
 * {@code INTERNAL_TRANSACTION_NOT_FOUND}, {@code INELIGIBLE_TRANSACTION_TYPE},
 * or {@code INTERNAL_LEDGER_INCONSISTENT}). Never includes
 * {@code account.balance}, raw CSV content, or any field beyond what is
 * already public via the existing settlement/ledger contracts.
 */
@Schema(description = "One settlement observation's reconciliation classification.")
public record ReconciliationResultResponse(
		@Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID resultId,
		@Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID settlementRecordId,
		@Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6") UUID reportedTransactionId,
		@Schema(example = "MATCHED") ReconciliationOutcome outcome,
		@Schema(example = "100.00") BigDecimal reportedAmount,
		@Schema(example = "USD") String reportedCurrency,
		@Schema(example = "100.0000", nullable = true) BigDecimal internalAmount,
		@Schema(example = "USD", nullable = true) String internalCurrency,
		@Schema(example = "2026-08-03T10:00:00Z") Instant createdAt) {
}
