package com.tarun.ledgerguard.account.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public response contract for GET /api/v1/accounts/{id}/balance, per
 * docs/API_SPEC.md. {@code balance} is the persisted materialized
 * {@code account.balance} column, read directly — never recomputed from
 * the ledger and never cached in application memory.
 */
public record AccountBalanceResponse(
		UUID accountId,
		BigDecimal balance,
		String currency
) {
}
