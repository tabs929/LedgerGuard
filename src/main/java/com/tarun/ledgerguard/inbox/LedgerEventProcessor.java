package com.tarun.ledgerguard.inbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Processes exactly one Kafka ledger event inside its own PostgreSQL
 * transaction — a separate Spring bean from {@code LedgerEventConsumer}
 * specifically so this class's {@code @Transactional} proxy boundary is
 * effective (self-invocation would silently bypass it, the same reason
 * {@code outbox.OutboxPublisher} is a separate bean from
 * {@code outbox.OutboxPublisherScheduler}).
 *
 * <p>Validates the record, computes the exact-payload SHA-256 fingerprint,
 * and atomically claims {@code processed_event} via {@link ProcessedEventRepository#tryClaim}.
 * Returning normally means the transaction committed and the caller may
 * acknowledge the Kafka offset; throwing means it rolled back and the
 * caller must not acknowledge. This method never mutates any account,
 * ledger, outbox, or idempotency state — the {@code processed_event}
 * insert is Task 13's only effect.
 */
@Component
public class LedgerEventProcessor {

	private final LedgerEventValidator validator;
	private final ProcessedEventRepository repository;

	public LedgerEventProcessor(LedgerEventValidator validator, ProcessedEventRepository repository) {
		this.validator = validator;
		this.repository = repository;
	}

	/**
	 * @throws LedgerEventValidationException the record fails strict
	 *         validation — no database mutation is attempted
	 * @throws ConflictingEventException {@code eventId} already exists
	 *         with different content — the existing row is left untouched
	 */
	@Transactional
	public void process(String topic, int partition, long offset, String kafkaKey, String kafkaValue) {
		ValidatedLedgerEvent event = validator.validate(kafkaKey, kafkaValue);
		String payloadHash = PayloadHasher.sha256Hex(kafkaValue);

		boolean claimed = repository.tryClaim(event.eventId(), event.aggregateId(), event.eventType(),
				event.schemaVersion(), payloadHash, topic, partition, offset);
		if (claimed) {
			// First delivery: the processed_event insert above is this
			// event's entire consumer-side effect.
			return;
		}

		ProcessedEventRecord existing = repository.findByEventId(event.eventId())
				.orElseThrow(() -> new IllegalStateException(
						"processed_event claim conflicted but no row was found for eventId " + event.eventId()));
		if (existing.matches(event, payloadHash)) {
			// Identical redelivery: a genuinely successful no-op, not a
			// mutation -- the caller may still acknowledge this offset.
			return;
		}
		throw new ConflictingEventException(event.eventId());
	}

}
