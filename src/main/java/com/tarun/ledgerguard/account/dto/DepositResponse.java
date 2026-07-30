package com.tarun.ledgerguard.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public response contract for a successful deposit, per docs/API_SPEC.md.
 * Never exposes the JPA entities (Account, LedgerTransaction, LedgerEntry).
 */
public record DepositResponse(
		UUID transactionId,
		UUID accountId,
		BigDecimal amount,
		String currency,
		BigDecimal newBalance,
		Instant createdAt
) {
}
