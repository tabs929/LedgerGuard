package com.tarun.ledgerguard.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publishes exactly one candidate outbox event per call, inside its own
 * PostgreSQL transaction — a separate Spring bean from
 * {@link OutboxPublisherScheduler} specifically so this class's
 * {@code @Transactional} proxy boundary is effective (self-invocation
 * from within the same bean would silently bypass Spring's transactional
 * AOP proxy).
 *
 * <p>Per event: begin a transaction, attempt to claim the row with
 * {@code FOR UPDATE SKIP LOCKED} (see
 * {@link OutboxEventRepository#lockPendingById}), and if — and only if —
 * the claim succeeds, send synchronously to Kafka and block for the
 * broker acknowledgement before ever touching {@code published_at}. A
 * send/acknowledgement failure throws, rolling back this transaction
 * alone (never any other candidate's), so the row stays pending for a
 * later polling cycle to retry.
 */
@Component
public class OutboxPublisher {

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

	private final OutboxEventRepository repository;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final OutboxPublisherProperties properties;

	public OutboxPublisher(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate,
			OutboxPublisherProperties properties) {
		this.repository = repository;
		this.kafkaTemplate = kafkaTemplate;
		this.properties = properties;
	}

	/**
	 * Attempts to publish one candidate. A no-op (no exception, no Kafka
	 * call) if the row is no longer pending or is currently locked by
	 * another publisher — see {@link OutboxEventRepository#lockPendingById}.
	 */
	@Transactional
	public void publishIfPending(UUID candidateId) {
		Optional<OutboxEvent> claimed = repository.lockPendingById(candidateId);
		if (claimed.isEmpty()) {
			return;
		}
		OutboxEvent event = claimed.get();

		waitForAcknowledgement(event);

		event.markPublished(Instant.now());
		// Flush now, inside this transaction, so a trigger/constraint
		// violation on this update (never expected under correct use --
		// the trigger allows exactly this one NULL -> non-null transition)
		// surfaces here rather than silently at a later, unrelated flush.
		repository.flush();
	}

	private void waitForAcknowledgement(OutboxEvent event) {
		try {
			kafkaTemplate.send(properties.getTopic(), event.getAggregateId().toString(), event.getPayload())
					.get(properties.getSendTimeoutMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Interrupted while publishing outbox event {} ({})", event.getId(), event.getEventType());
			throw new OutboxPublishException(event.getId(), e);
		}
		catch (ExecutionException | TimeoutException e) {
			log.warn("Failed to publish outbox event {} ({}): {}", event.getId(), event.getEventType(),
					e.getClass().getSimpleName());
			throw new OutboxPublishException(event.getId(), e);
		}
	}

}
