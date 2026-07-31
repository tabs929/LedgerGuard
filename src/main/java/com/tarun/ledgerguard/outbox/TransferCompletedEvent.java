package com.tarun.ledgerguard.outbox;

import java.util.UUID;

/**
 * The version-1 {@code TRANSFER_COMPLETED} event payload — exactly the
 * fields in the approved Task 11 contract, no more. See
 * {@link DepositCompletedEvent} for the {@code amount}/{@code occurredAt}
 * formatting rationale.
 */
public record TransferCompletedEvent(
		UUID eventId,
		String eventType,
		int schemaVersion,
		String occurredAt,
		UUID transactionId,
		UUID sourceAccountId,
		UUID destinationAccountId,
		String amount,
		String currency) {
}
