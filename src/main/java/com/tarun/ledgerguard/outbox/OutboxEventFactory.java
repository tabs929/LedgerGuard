package com.tarun.ledgerguard.outbox;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * The single reusable insertion point {@code DepositService}/
 * {@code TransferService} call after building a completed ledger
 * transaction and before returning their response — always inside that
 * same ambient {@code @Transactional} method (no {@code REQUIRES_NEW}, no
 * after-commit hook), so the resulting {@code outbox_event} row commits or
 * rolls back atomically with the ledger transaction, the ledger entries,
 * the balance updates, and the Task 10 idempotency record it's nested
 * inside. A serialization failure here is a transactional failure like
 * any other — it propagates and rolls back the whole operation, it is
 * never caught and suppressed.
 *
 * <p>Because this is only ever called from the private
 * {@code doDeposit}/{@code doTransfer} methods — i.e. only on the branch
 * of {@code IdempotencyService.execute} that actually performs a new
 * financial write — a Task 10 replay or conflict can never reach this
 * class, so no code here needs to guard against creating a duplicate
 * event; {@code uq_outbox_event_identity} in the V3 migration is a
 * database-level backstop for that same guarantee.
 */
@Component
public class OutboxEventFactory {

	private static final int SCHEMA_VERSION = 1;

	private final OutboxEventRepository repository;
	private final ObjectMapper objectMapper;

	public OutboxEventFactory(OutboxEventRepository repository, ObjectMapper objectMapper) {
		this.repository = repository;
		this.objectMapper = objectMapper;
	}

	public void recordDepositCompleted(UUID transactionId, UUID destinationAccountId, BigDecimal amount,
			String currency, Instant occurredAt) {
		UUID eventId = UUID.randomUUID();
		DepositCompletedEvent payload = new DepositCompletedEvent(
				eventId,
				OutboxEventType.DEPOSIT_COMPLETED.name(),
				SCHEMA_VERSION,
				formatOccurredAt(occurredAt),
				transactionId,
				destinationAccountId,
				amount.toPlainString(),
				currency);
		persist(eventId, transactionId, OutboxEventType.DEPOSIT_COMPLETED, occurredAt, payload);
	}

	public void recordTransferCompleted(UUID transactionId, UUID sourceAccountId, UUID destinationAccountId,
			BigDecimal amount, String currency, Instant occurredAt) {
		UUID eventId = UUID.randomUUID();
		TransferCompletedEvent payload = new TransferCompletedEvent(
				eventId,
				OutboxEventType.TRANSFER_COMPLETED.name(),
				SCHEMA_VERSION,
				formatOccurredAt(occurredAt),
				transactionId,
				sourceAccountId,
				destinationAccountId,
				amount.toPlainString(),
				currency);
		persist(eventId, transactionId, OutboxEventType.TRANSFER_COMPLETED, occurredAt, payload);
	}

	private void persist(UUID eventId, UUID aggregateId, OutboxEventType eventType, Instant occurredAt,
			Object payload) {
		String json = serialize(payload, eventId);
		OutboxEvent event = new OutboxEvent(eventId, OutboxAggregateType.LEDGER_TRANSACTION, aggregateId, eventType,
				SCHEMA_VERSION, json, occurredAt);
		repository.save(event);
		// Flush now so a constraint violation (e.g. the uq_outbox_event_identity
		// backstop) surfaces here, inside this transaction, rolling back the
		// whole deposit/transfer -- not at some later, unrelated flush point.
		repository.flush();
	}

	private String serialize(Object payload, UUID eventId) {
		try {
			return objectMapper.writeValueAsString(payload);
		} catch (JacksonException e) {
			throw new IllegalStateException("Failed to serialize outbox event " + eventId, e);
		}
	}

	// ISO-8601 UTC, deterministic and locale-independent -- not left to
	// Jackson's default Instant handling.
	private String formatOccurredAt(Instant occurredAt) {
		return DateTimeFormatter.ISO_INSTANT.format(occurredAt);
	}

}
