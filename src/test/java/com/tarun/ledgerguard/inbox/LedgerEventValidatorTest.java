package com.tarun.ledgerguard.inbox;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Focused unit coverage (Task 13) for {@link LedgerEventValidator} — no
 * Kafka or PostgreSQL involved; every rejection case is exercised
 * directly against a raw JSON string, mirroring exactly what a Kafka
 * record value would contain.
 */
class LedgerEventValidatorTest {

	private final LedgerEventValidator validator = new LedgerEventValidator(JsonMapper.builder().build());

	private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID TRANSACTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID DESTINATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID SOURCE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

	// ------------------------------------------------------------------
	// valid events
	// ------------------------------------------------------------------

	@Test
	void validDepositEventIsAccepted() {
		ValidatedLedgerEvent event = validator.validate(TRANSACTION_ID.toString(), depositJson());
		assertThat(event.eventId()).isEqualTo(EVENT_ID);
		assertThat(event.aggregateId()).isEqualTo(TRANSACTION_ID);
		assertThat(event.eventType()).isEqualTo("DEPOSIT_COMPLETED");
		assertThat(event.schemaVersion()).isEqualTo(1);
	}

	@Test
	void validTransferEventIsAccepted() {
		ValidatedLedgerEvent event = validator.validate(TRANSACTION_ID.toString(), transferJson());
		assertThat(event.eventType()).isEqualTo("TRANSFER_COMPLETED");
	}

	// ------------------------------------------------------------------
	// structural rejection
	// ------------------------------------------------------------------

	@Test
	void malformedJsonIsRejected() {
		assertRejected(TRANSACTION_ID.toString(), "{not valid json");
	}

	@Test
	void jsonArrayIsRejected() {
		assertRejected(TRANSACTION_ID.toString(), "[1,2,3]");
	}

	@Test
	void jsonScalarIsRejected() {
		assertRejected(TRANSACTION_ID.toString(), "\"just a string\"");
	}

	@Test
	void jsonNullIsRejected() {
		assertRejected(TRANSACTION_ID.toString(), "null");
	}

	@Test
	void missingFieldIsRejected() {
		String json = depositJsonMissing("currency");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void unexpectedFieldIsRejected() {
		String json = depositJson().replace("}", ",\"extra\":\"field\"}");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void nullFieldIsRejected() {
		String json = depositJson().replace("\"currency\": \"USD\"", "\"currency\": null");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	// ------------------------------------------------------------------
	// field-format rejection
	// ------------------------------------------------------------------

	@Test
	void invalidEventIdIsRejected() {
		String json = depositJson().replace(EVENT_ID.toString(), "not-a-uuid");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void invalidTransactionIdIsRejected() {
		String json = depositJson().replace(TRANSACTION_ID.toString(), "not-a-uuid");
		assertRejected("not-a-uuid", json);
	}

	@Test
	void invalidOccurredAtIsRejected() {
		String json = depositJson().replace("2026-07-31T12:00:00Z", "not-a-timestamp");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void unknownEventTypeIsRejected() {
		String json = depositJson().replace("DEPOSIT_COMPLETED", "SOMETHING_ELSE");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void unsupportedSchemaVersionIntegerIsRejected() {
		String json = depositJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void unsupportedSchemaVersionZeroIsRejected() {
		String json = depositJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 0");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void schemaVersionAsStringIsRejected() {
		String json = depositJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": \"1\"");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void schemaVersionAsFloatingPointIsRejected() {
		String json = depositJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 1.0");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void kafkaKeyMismatchWithTransactionIdIsRejected() {
		assertRejected(UUID.randomUUID().toString(), depositJson());
	}

	@Test
	void jsonNumericAmountIsRejected() {
		String json = depositJson().replace("\"amount\": \"100.0000\"", "\"amount\": 100.0000");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void incorrectlyScaledAmountIsRejected() {
		String json = depositJson().replace("100.0000", "100.00");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void amountWithNoFractionalDigitsIsRejected() {
		String json = depositJson().replace("\"100.0000\"", "\"100\"");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void zeroAmountIsRejected() {
		String json = depositJson().replace("100.0000", "0.0000");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void negativeAmountIsRejected() {
		String json = depositJson().replace("\"100.0000\"", "\"-100.0000\"");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void lowercaseCurrencyIsRejected() {
		String json = depositJson().replace("\"USD\"", "\"usd\"");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void unsupportedCurrencyIsRejected() {
		String json = depositJson().replace("\"USD\"", "\"EUR\"");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	// ------------------------------------------------------------------
	// event-type-specific rules
	// ------------------------------------------------------------------

	@Test
	void depositPayloadContainingTransferOnlyFieldIsRejected() {
		String json = depositJson().replace("}", ",\"sourceAccountId\":\"" + SOURCE_ID + "\"}");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void transferPayloadMissingSourceAccountIdIsRejected() {
		String json = transferJsonMissing("sourceAccountId");
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	@Test
	void transferWithIdenticalSourceAndDestinationIsRejected() {
		String json = transferJson().replace(SOURCE_ID.toString(), DESTINATION_ID.toString());
		assertRejected(TRANSACTION_ID.toString(), json);
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private void assertRejected(String kafkaKey, String json) {
		assertThatThrownBy(() -> validator.validate(kafkaKey, json))
				.isInstanceOf(LedgerEventValidationException.class);
	}

	private String depositJson() {
		return """
				{
				  "eventId": "%s",
				  "eventType": "DEPOSIT_COMPLETED",
				  "schemaVersion": 1,
				  "occurredAt": "2026-07-31T12:00:00Z",
				  "transactionId": "%s",
				  "destinationAccountId": "%s",
				  "amount": "100.0000",
				  "currency": "USD"
				}
				""".formatted(EVENT_ID, TRANSACTION_ID, DESTINATION_ID);
	}

	private String depositJsonMissing(String field) {
		return switch (field) {
			case "currency" -> """
					{
					  "eventId": "%s",
					  "eventType": "DEPOSIT_COMPLETED",
					  "schemaVersion": 1,
					  "occurredAt": "2026-07-31T12:00:00Z",
					  "transactionId": "%s",
					  "destinationAccountId": "%s",
					  "amount": "100.0000"
					}
					""".formatted(EVENT_ID, TRANSACTION_ID, DESTINATION_ID);
			default -> throw new IllegalArgumentException("unsupported field: " + field);
		};
	}

	private String transferJson() {
		return """
				{
				  "eventId": "%s",
				  "eventType": "TRANSFER_COMPLETED",
				  "schemaVersion": 1,
				  "occurredAt": "2026-07-31T12:00:00Z",
				  "transactionId": "%s",
				  "sourceAccountId": "%s",
				  "destinationAccountId": "%s",
				  "amount": "30.0000",
				  "currency": "USD"
				}
				""".formatted(EVENT_ID, TRANSACTION_ID, SOURCE_ID, DESTINATION_ID);
	}

	private String transferJsonMissing(String field) {
		return switch (field) {
			case "sourceAccountId" -> """
					{
					  "eventId": "%s",
					  "eventType": "TRANSFER_COMPLETED",
					  "schemaVersion": 1,
					  "occurredAt": "2026-07-31T12:00:00Z",
					  "transactionId": "%s",
					  "destinationAccountId": "%s",
					  "amount": "30.0000",
					  "currency": "USD"
					}
					""".formatted(EVENT_ID, TRANSACTION_ID, DESTINATION_ID);
			default -> throw new IllegalArgumentException("unsupported field: " + field);
		};
	}

}
