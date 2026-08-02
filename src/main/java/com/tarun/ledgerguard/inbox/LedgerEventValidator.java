package com.tarun.ledgerguard.inbox;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict, hand-written validation of a Task 11/12 version-1 ledger event —
 * deliberately not delegated to Jackson's default record-binding
 * (unknown-property rejection alone can't express "amount must be a
 * positive four-decimal string" or "source and destination must differ"),
 * and deliberately not shared global Jackson configuration (this
 * validator is Kafka-consumption-specific; it does not change how the
 * rest of the application deserializes JSON). Uses the application's
 * real {@code tools.jackson.databind.ObjectMapper} bean (Jackson 3) only
 * to parse the raw string into a tree for field-by-field inspection —
 * never to bind directly into a lenient DTO.
 *
 * <p>Every rejection throws {@link LedgerEventValidationException} with a
 * safe, generic message — never the offending payload content.
 */
@Component
public class LedgerEventValidator {

	private static final Set<String> DEPOSIT_FIELDS = Set.of(
			"eventId", "eventType", "schemaVersion", "occurredAt", "transactionId",
			"destinationAccountId", "amount", "currency");

	private static final Set<String> TRANSFER_FIELDS = Set.of(
			"eventId", "eventType", "schemaVersion", "occurredAt", "transactionId",
			"sourceAccountId", "destinationAccountId", "amount", "currency");

	// Exactly four fractional digits, no sign, matching the Task 11
	// normalized wire format (e.g. "100.0000") -- never a bare JSON number.
	private static final Pattern AMOUNT_PATTERN = Pattern.compile("^\\d+\\.\\d{4}$");

	private final ObjectMapper objectMapper;

	public LedgerEventValidator(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public ValidatedLedgerEvent validate(String kafkaKey, String kafkaValue) {
		JsonNode root = parse(kafkaValue);

		String eventType = requireText(root, "eventType");
		Set<String> allowedFields = switch (eventType) {
			case "DEPOSIT_COMPLETED" -> DEPOSIT_FIELDS;
			case "TRANSFER_COMPLETED" -> TRANSFER_FIELDS;
			default -> throw new LedgerEventValidationException("Unsupported eventType");
		};

		Set<String> actualFields = new HashSet<>();
		root.propertyNames().forEach(actualFields::add);
		if (!actualFields.equals(allowedFields)) {
			throw new LedgerEventValidationException(
					"Unexpected or missing fields for eventType " + eventType);
		}

		UUID eventId = requireUuid(root, "eventId");
		requireSchemaVersionOne(root);
		requireInstant(root, "occurredAt");
		UUID transactionId = requireUuid(root, "transactionId");
		UUID destinationAccountId = requireUuid(root, "destinationAccountId");
		requireFourDecimalPositiveAmount(root);
		requireUsdCurrency(root);

		if (kafkaKey == null || !kafkaKey.equals(transactionId.toString())) {
			throw new LedgerEventValidationException("Kafka record key does not match transactionId");
		}

		if ("TRANSFER_COMPLETED".equals(eventType)) {
			UUID sourceAccountId = requireUuid(root, "sourceAccountId");
			if (sourceAccountId.equals(destinationAccountId)) {
				throw new LedgerEventValidationException("sourceAccountId and destinationAccountId must differ");
			}
		}

		return new ValidatedLedgerEvent(eventId, transactionId, eventType, 1);
	}

	private JsonNode parse(String kafkaValue) {
		JsonNode root;
		try {
			root = objectMapper.readTree(kafkaValue);
		}
		catch (JacksonException e) {
			throw new LedgerEventValidationException("Malformed JSON payload");
		}
		if (root == null || !root.isObject()) {
			throw new LedgerEventValidationException("Payload is not a JSON object");
		}
		return root;
	}

	private String requireText(JsonNode root, String field) {
		JsonNode node = root.get(field);
		if (node == null || node.isNull() || !node.isTextual() || node.asText().isBlank()) {
			throw new LedgerEventValidationException("Missing or invalid field: " + field);
		}
		return node.asText();
	}

	private UUID requireUuid(JsonNode root, String field) {
		String text = requireText(root, field);
		try {
			return UUID.fromString(text);
		}
		catch (IllegalArgumentException e) {
			throw new LedgerEventValidationException("Invalid UUID for field: " + field);
		}
	}

	private void requireSchemaVersionOne(JsonNode root) {
		JsonNode node = root.get("schemaVersion");
		if (node == null || node.isNull() || !node.isIntegralNumber() || node.asInt(-1) != 1) {
			throw new LedgerEventValidationException("Unsupported schemaVersion");
		}
	}

	private void requireInstant(JsonNode root, String field) {
		String text = requireText(root, field);
		try {
			Instant.parse(text);
		}
		catch (DateTimeParseException e) {
			throw new LedgerEventValidationException("Invalid timestamp for field: " + field);
		}
	}

	private void requireFourDecimalPositiveAmount(JsonNode root) {
		JsonNode node = root.get("amount");
		if (node == null || node.isNull() || !node.isTextual()) {
			throw new LedgerEventValidationException("amount must be a JSON string");
		}
		String text = node.asText();
		if (!AMOUNT_PATTERN.matcher(text).matches()) {
			throw new LedgerEventValidationException("amount must have exactly four fractional digits");
		}
		if (new BigDecimal(text).signum() <= 0) {
			throw new LedgerEventValidationException("amount must be positive");
		}
	}

	private void requireUsdCurrency(JsonNode root) {
		String text = requireText(root, "currency");
		if (!"USD".equals(text)) {
			throw new LedgerEventValidationException("Unsupported currency");
		}
	}

}
