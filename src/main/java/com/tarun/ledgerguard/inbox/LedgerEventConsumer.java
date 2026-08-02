package com.tarun.ledgerguard.inbox;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The Spring Kafka listener for {@code ledger.transaction-events.v1} —
 * receives one {@link ConsumerRecord}, delegates entirely to
 * {@link LedgerEventProcessor} (a separate bean, so its
 * {@code @Transactional} proxy is effective), and acknowledges only after
 * that call returns successfully. Contains no financial state mutation
 * and no parsing/validation of its own.
 *
 * <p><b>Acknowledgement ordering.</b> The container is configured for
 * manual-immediate acknowledgement with {@code enable.auto.commit=false}
 * (see {@link LedgerEventConsumerConfig}), so nothing here can commit a
 * Kafka offset before {@code processor.process(...)} has returned — and
 * that method is {@code @Transactional}, so it cannot return successfully
 * until its PostgreSQL transaction has committed. The required order
 * (validate → PostgreSQL commit → Kafka acknowledgement) therefore falls
 * out of this structure by construction, not by a manually sequenced
 * try/finally.
 *
 * <p><b>Failure handling.</b> On any failure — validation, a conflicting
 * event, or a transient error such as a database outage — this calls
 * {@link Acknowledgment#nack(Duration)} rather than silently continuing.
 * {@code nack} re-seeks the consumer back to this record (and everything
 * already fetched after it on the same partition) so it is redelivered
 * after a bounded backoff, rather than merely leaving it unacknowledged:
 * with manual-immediate acknowledgement, a later record on the same
 * partition being acknowledged would otherwise silently advance the
 * committed offset past an earlier record that was only ever left
 * un-acked (Kafka's commit is a single per-partition cursor, not a
 * per-record ledger) — {@code nack} is what actually prevents that.
 * Every failure category uses the same fixed, bounded backoff and the
 * same non-skipping behavior; a permanently invalid or conflicting record
 * therefore keeps its partition positioned at that record until it is
 * corrected or removed upstream — a deliberate, documented limitation
 * (see docs/ARCHITECTURE.md's "Kafka Consumption" section); dead-letter
 * handling for that scenario is out of scope for Task 13.
 */
@Component
@ConditionalOnProperty(prefix = "ledgerguard.inbox.consumer", name = "enabled", havingValue = "true")
public class LedgerEventConsumer {

	private static final Logger log = LoggerFactory.getLogger(LedgerEventConsumer.class);

	// Fixed, bounded redelivery backoff for every failure category -- not
	// configurable in Task 13 (only enabled/topic/group-id/concurrency/
	// auto-offset-reset/poll-timeout are, per the approved contract).
	private static final Duration REDELIVERY_BACKOFF = Duration.ofSeconds(2);

	private final LedgerEventProcessor processor;

	public LedgerEventConsumer(LedgerEventProcessor processor) {
		this.processor = processor;
	}

	@KafkaListener(
			topics = "${ledgerguard.inbox.consumer.topic}",
			groupId = "${ledgerguard.inbox.consumer.group-id}",
			concurrency = "${ledgerguard.inbox.consumer.concurrency}",
			containerFactory = LedgerEventConsumerConfig.CONTAINER_FACTORY_BEAN_NAME)
	public void onMessage(ConsumerRecord<String, String> record, Acknowledgment acknowledgment) {
		try {
			processor.process(record.topic(), record.partition(), record.offset(), record.key(), record.value());
			acknowledgment.acknowledge();
		}
		catch (LedgerEventValidationException | ConflictingEventException e) {
			// Permanent failure: never safe to skip. Safe diagnostic only
			// -- never the payload, never a stack trace here.
			log.warn("Rejected ledger event: topic={} partition={} offset={} key={} reason={}",
					record.topic(), record.partition(), record.offset(), record.key(), e.getMessage());
			acknowledgment.nack(REDELIVERY_BACKOFF);
		}
		catch (Exception e) {
			// Transient failure (e.g. a database outage): also never safe
			// to skip -- redelivery after backoff gives the dependency a
			// chance to recover.
			log.warn("Transient failure processing ledger event: topic={} partition={} offset={} key={} category={}",
					record.topic(), record.partition(), record.offset(), record.key(), e.getClass().getSimpleName());
			acknowledgment.nack(REDELIVERY_BACKOFF);
		}
	}

}
