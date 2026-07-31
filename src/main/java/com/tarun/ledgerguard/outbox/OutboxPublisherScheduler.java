package com.tarun.ledgerguard.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Periodically polls for pending {@code outbox_event} rows and hands each
 * candidate id to {@link OutboxPublisher} one at a time — coordination
 * only, no PostgreSQL transaction of its own and no direct Kafka call.
 * Deliberately a separate bean from {@link OutboxPublisher} so that
 * class's {@code @Transactional} proxy applies per-candidate rather than
 * wrapping the whole batch in one transaction (see
 * docs/ARCHITECTURE.md's "Kafka Publishing" section for why a
 * whole-batch transaction would turn an earlier successfully-acknowledged
 * send into an avoidable duplicate whenever a later send in the same
 * batch fails).
 *
 * <p>Conditional on {@code ledgerguard.outbox.publisher.enabled=true} —
 * every PostgreSQL-only integration test suite sets this to {@code false}
 * (see {@code application-test.yml}) specifically so it never attempts a
 * Kafka connection.
 */
@Component
@ConditionalOnProperty(prefix = "ledgerguard.outbox.publisher", name = "enabled", havingValue = "true")
public class OutboxPublisherScheduler {

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);

	private final OutboxEventRepository repository;
	private final OutboxPublisher publisher;
	private final OutboxPublisherProperties properties;

	public OutboxPublisherScheduler(OutboxEventRepository repository, OutboxPublisher publisher,
			OutboxPublisherProperties properties) {
		this.repository = repository;
		this.publisher = publisher;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${ledgerguard.outbox.publisher.poll-delay-millis}")
	public void pollAndPublishPendingEvents() {
		List<UUID> candidateIds = repository.findPendingCandidateIds(properties.getBatchSize());
		for (UUID candidateId : candidateIds) {
			publishOneCandidate(candidateId);
		}
	}

	// One candidate's failure must never stop the batch -- log and move on
	// so every later candidate still gets its own attempt this cycle.
	private void publishOneCandidate(UUID candidateId) {
		try {
			publisher.publishIfPending(candidateId);
		}
		catch (Exception e) {
			log.warn("Skipping outbox event {} this cycle after a publish failure: {}", candidateId,
					e.getClass().getSimpleName());
		}
	}

}
