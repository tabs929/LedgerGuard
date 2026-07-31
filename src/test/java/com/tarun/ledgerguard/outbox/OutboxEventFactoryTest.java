package com.tarun.ledgerguard.outbox;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * Focused unit coverage for {@link OutboxEventFactory}'s payload
 * construction and monetary/timestamp serialization — no database
 * involved (the repository is mocked; PostgreSQL-backed atomicity,
 * constraints, and replay/rollback behavior are covered instead by
 * {@code OutboxIntegrationTest} against real Testcontainers, per
 * docs/TEST_STRATEGY.md's split).
 */
@ExtendWith(MockitoExtension.class)
class OutboxEventFactoryTest {

	@Mock
	OutboxEventRepository repository;

	private final JsonMapper objectMapper = JsonMapper.builder().build();

	@Test
	void depositEventPayloadContainsExactlyTheApprovedFields() {
		OutboxEventFactory factory = new OutboxEventFactory(repository, objectMapper);
		UUID transactionId = UUID.randomUUID();
		UUID destinationAccountId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-07-31T12:00:00.123456Z");

		factory.recordDepositCompleted(transactionId, destinationAccountId, new BigDecimal("100.0000"), "USD",
				occurredAt);

		OutboxEvent event = captureSavedEvent();
		assertThat(event.getAggregateType()).isEqualTo(OutboxAggregateType.LEDGER_TRANSACTION);
		assertThat(event.getAggregateId()).isEqualTo(transactionId);
		assertThat(event.getEventType()).isEqualTo(OutboxEventType.DEPOSIT_COMPLETED);
		assertThat(event.getSchemaVersion()).isEqualTo(1);
		assertThat(event.getOccurredAt()).isEqualTo(occurredAt);

		JsonNode payload = objectMapper.readTree(event.getPayload());
		assertThat(payload.propertyNames()).containsExactlyInAnyOrder(
				"eventId", "eventType", "schemaVersion", "occurredAt", "transactionId",
				"destinationAccountId", "amount", "currency");
		assertThat(payload.get("eventId").asText()).isEqualTo(event.getId().toString());
		assertThat(payload.get("eventType").asText()).isEqualTo("DEPOSIT_COMPLETED");
		assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
		assertThat(payload.get("transactionId").asText()).isEqualTo(transactionId.toString());
		assertThat(payload.get("destinationAccountId").asText()).isEqualTo(destinationAccountId.toString());
		assertThat(payload.get("currency").asText()).isEqualTo("USD");
	}

	@Test
	void transferEventPayloadContainsExactlyTheApprovedFields() {
		OutboxEventFactory factory = new OutboxEventFactory(repository, objectMapper);
		UUID transactionId = UUID.randomUUID();
		UUID sourceAccountId = UUID.randomUUID();
		UUID destinationAccountId = UUID.randomUUID();
		Instant occurredAt = Instant.parse("2026-07-31T12:00:00Z");

		factory.recordTransferCompleted(transactionId, sourceAccountId, destinationAccountId,
				new BigDecimal("30.0000"), "USD", occurredAt);

		OutboxEvent event = captureSavedEvent();
		assertThat(event.getEventType()).isEqualTo(OutboxEventType.TRANSFER_COMPLETED);
		assertThat(event.getAggregateId()).isEqualTo(transactionId);

		JsonNode payload = objectMapper.readTree(event.getPayload());
		assertThat(payload.propertyNames()).containsExactlyInAnyOrder(
				"eventId", "eventType", "schemaVersion", "occurredAt", "transactionId",
				"sourceAccountId", "destinationAccountId", "amount", "currency");
		assertThat(payload.get("eventType").asText()).isEqualTo("TRANSFER_COMPLETED");
		assertThat(payload.get("sourceAccountId").asText()).isEqualTo(sourceAccountId.toString());
		assertThat(payload.get("destinationAccountId").asText()).isEqualTo(destinationAccountId.toString());
	}

	@Test
	void amountSerializesAsAFixedFourDecimalStringNeverAFloatingPointNumber() {
		OutboxEventFactory factory = new OutboxEventFactory(repository, objectMapper);
		Instant occurredAt = Instant.parse("2026-07-31T12:00:00Z");

		// A whole-number amount must still serialize with all four decimal
		// places -- "5.0000", never "5" and never a bare JSON number.
		factory.recordDepositCompleted(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("5.0000"), "USD",
				occurredAt);

		JsonNode payload = objectMapper.readTree(captureSavedEvent().getPayload());
		assertThat(payload.get("amount").isTextual())
				.as("amount must be a JSON string, not a floating-point JSON number")
				.isTrue();
		assertThat(payload.get("amount").asText()).isEqualTo("5.0000");
	}

	@Test
	void occurredAtSerializesAsIso8601UtcAndMatchesTheStoredColumnValue() {
		OutboxEventFactory factory = new OutboxEventFactory(repository, objectMapper);
		Instant occurredAt = Instant.parse("2026-07-31T18:45:30.500000Z");

		factory.recordDepositCompleted(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1.0000"), "USD",
				occurredAt);

		OutboxEvent event = captureSavedEvent();
		JsonNode payload = objectMapper.readTree(event.getPayload());
		assertThat(Instant.parse(payload.get("occurredAt").asText())).isEqualTo(occurredAt);
		assertThat(event.getOccurredAt()).isEqualTo(occurredAt);
	}

	@Test
	void schemaVersionIsOneForBothEventTypes() {
		OutboxEventFactory factory = new OutboxEventFactory(repository, objectMapper);
		Instant occurredAt = Instant.parse("2026-07-31T12:00:00Z");

		factory.recordDepositCompleted(UUID.randomUUID(), UUID.randomUUID(), new BigDecimal("1.0000"), "USD",
				occurredAt);
		assertThat(captureSavedEvent().getSchemaVersion()).isEqualTo(1);

		factory.recordTransferCompleted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				new BigDecimal("1.0000"), "USD", occurredAt);
		assertThat(captureSavedEvent().getSchemaVersion()).isEqualTo(1);
	}

	@Test
	void eventIdIsARandomUuidNotDerivedFromMutableOrHashedValues() {
		OutboxEventFactory factory = new OutboxEventFactory(repository, objectMapper);
		Instant occurredAt = Instant.parse("2026-07-31T12:00:00Z");
		UUID transactionId = UUID.randomUUID();

		factory.recordDepositCompleted(transactionId, UUID.randomUUID(), new BigDecimal("1.0000"), "USD", occurredAt);
		UUID firstEventId = captureSavedEvent().getId();

		factory.recordDepositCompleted(transactionId, UUID.randomUUID(), new BigDecimal("1.0000"), "USD", occurredAt);
		UUID secondEventId = captureSavedEvent().getId();

		// Same transactionId, same amount/currency/occurredAt -- if eventId
		// were derived from hashCode or the other fields, these would
		// collide. They must not.
		assertThat(firstEventId).isNotEqualTo(secondEventId);
	}

	private OutboxEvent captureSavedEvent() {
		ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
		verify(repository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
		return captor.getValue();
	}

}
