package com.tarun.ledgerguard.transfer.dto;

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
		UUID transactionId,
		UUID sourceAccountId,
		UUID destinationAccountId,
		BigDecimal amount,
		String currency,
		Instant createdAt
) {
}
