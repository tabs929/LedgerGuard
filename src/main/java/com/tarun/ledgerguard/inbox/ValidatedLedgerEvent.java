package com.tarun.ledgerguard.inbox;

import java.util.UUID;

/**
 * The minimal, already-validated shape {@link LedgerEventValidator}
 * produces from a Kafka record's key/value — exactly the fields
 * {@link LedgerEventProcessor} needs to claim {@code processed_event}.
 * Task 13 does not persist the full deposit/transfer payload (see
 * docs/DATA_MODEL.md's "Processed Event Table" section for why), so this
 * type deliberately carries no more than {@code eventId}, the aggregate
 * id ({@code transactionId} in the wire payload), {@code eventType}, and
 * {@code schemaVersion}.
 */
public record ValidatedLedgerEvent(UUID eventId, UUID aggregateId, String eventType, int schemaVersion) {
}
