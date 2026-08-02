package com.tarun.ledgerguard.inbox;

import java.time.Instant;
import java.util.UUID;

/**
 * A plain read projection of one {@code processed_event} row (Flyway
 * {@code V4}) — not a JPA entity, since {@link ProcessedEventRepository}
 * is deliberately implemented with {@code NamedParameterJdbcTemplate}
 * rather than normal JPA persistence (see that class's Javadoc for why).
 */
public record ProcessedEventRecord(
		UUID eventId,
		UUID aggregateId,
		String eventType,
		int schemaVersion,
		String payloadHash,
		String sourceTopic,
		int sourcePartition,
		long sourceOffset,
		Instant processedAt) {

	/**
	 * Whether this already-committed row represents the exact same event
	 * as {@code event}/{@code payloadHash} — never comparing source
	 * topic/partition/offset, since a legitimate redelivery of the same
	 * event may land at a different Kafka position.
	 */
	public boolean matches(ValidatedLedgerEvent event, String payloadHash) {
		return this.aggregateId.equals(event.aggregateId())
				&& this.eventType.equals(event.eventType())
				&& this.schemaVersion == event.schemaVersion()
				&& this.payloadHash.equals(payloadHash);
	}

}
