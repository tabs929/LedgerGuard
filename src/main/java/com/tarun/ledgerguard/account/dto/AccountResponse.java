package com.tarun.ledgerguard.account.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Public response contract for account creation, per docs/API_SPEC.md.
 * Never exposes the JPA entity, and carries only the fields a public
 * caller is meant to see (no account category/class/purpose).
 */
public record AccountResponse(
		UUID id,
		String ownerName,
		String currency,
		BigDecimal balance,
		Instant createdAt
) {
}
