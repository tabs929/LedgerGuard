package com.tarun.ledgerguard.settlement;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict, RFC 4180-compliant parsing and validation of a settlement CSV
 * file, per docs/API_SPEC.md's "Settlement CSV Contract". Contains no
 * database access -- every method here is a pure function from bytes to
 * either a validated {@link SettlementCsvRow} list or a thrown, safely
 * messaged exception. Never logs or echoes raw file/row content (see
 * {@code InvalidSettlementRequestException} usages below -- every message
 * is a fixed string plus a row number and/or a hardcoded field name,
 * never the submitted value itself).
 *
 * <p><b>Within-file duplicate identity policy:</b> a repeated
 * {@code external_reference} within a single file -- whether the repeated
 * rows are byte-identical or conflicting -- unconditionally rejects the
 * whole file (400) here, before any persistence is attempted. Task 14's
 * specification allows either "reject all repeats" or "tolerate an
 * identical repeat, reject only a conflicting repeat" as long as the
 * choice is documented; this implementation takes the simpler, more
 * unambiguous option (reject all repeats) rather than the more permissive
 * one, since a byte-identical intra-file repeat is itself almost always a
 * sign of an upstream export bug and gives the caller zero benefit over
 * simply re-submitting a corrected file.
 */
@Component
class SettlementCsvParser {

	private static final List<String> EXPECTED_HEADER = List.of(
			"external_reference", "transaction_id", "amount", "currency", "settled_at");

	// Up to 17 integer digits + exactly 2 decimal digits, matching
	// NUMERIC(19,2). No sign (amount must be > 0, checked separately, so
	// leading '-' is never accepted here), no exponent, no thousands
	// separators.
	private static final Pattern AMOUNT_PATTERN = Pattern.compile("^[0-9]{1,17}\\.[0-9]{2}$");
	private static final Pattern CURRENCY_FORMAT_PATTERN = Pattern.compile("^[A-Z]{3}$");
	private static final Pattern CONTROL_CHARACTER_PATTERN = Pattern.compile("\\p{Cntrl}");

	// Mirrors account.AccountService's Phase 1 supported-currency list.
	// Deliberately duplicated rather than importing account-package
	// internals here -- Task 14 must not modify or take on a runtime
	// dependency on account/deposit/transfer code. Keep in sync manually
	// if a future task introduces a shared currency registry.
	private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD");

	private static final byte[] UTF8_BOM = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };

	List<SettlementCsvRow> parse(byte[] rawBytes, String normalizedSource, SettlementImportProperties properties) {
		byte[] content = stripBom(rawBytes);
		String text = decodeStrictUtf8(content);

		List<CSVRecord> records = readRecords(text);
		if (records.isEmpty()) {
			throw new InvalidSettlementRequestException("file: must not be empty");
		}

		validateHeader(records.get(0));

		if (records.size() == 1) {
			throw new InvalidSettlementRequestException("file: must contain at least one data row");
		}

		int dataRowCount = records.size() - 1;
		if (dataRowCount > properties.getMaxRowCount()) {
			throw new SettlementRowLimitExceededException(properties.getMaxRowCount());
		}

		List<SettlementCsvRow> rows = new ArrayList<>(dataRowCount);
		Set<String> seenExternalReferences = new HashSet<>();
		for (int i = 1; i < records.size(); i++) {
			int sourceRowNumber = i;
			SettlementCsvRow row = parseRow(records.get(i), sourceRowNumber, normalizedSource, properties);
			if (!seenExternalReferences.add(row.externalReference())) {
				throw new InvalidSettlementRequestException(
						"row " + sourceRowNumber + ": external_reference is repeated within this file");
			}
			rows.add(row);
		}
		return rows;
	}

	private byte[] stripBom(byte[] rawBytes) {
		if (rawBytes.length >= UTF8_BOM.length
				&& rawBytes[0] == UTF8_BOM[0] && rawBytes[1] == UTF8_BOM[1] && rawBytes[2] == UTF8_BOM[2]) {
			return Arrays.copyOfRange(rawBytes, UTF8_BOM.length, rawBytes.length);
		}
		return rawBytes;
	}

	private String decodeStrictUtf8(byte[] content) {
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			CharBuffer decoded = decoder.decode(ByteBuffer.wrap(content));
			return decoded.toString();
		} catch (CharacterCodingException e) {
			throw new InvalidSettlementRequestException("file: not valid UTF-8");
		}
	}

	private List<CSVRecord> readRecords(String text) {
		CSVFormat format = CSVFormat.Builder.create()
				.setDelimiter(',')
				.setQuote('"')
				// CRLF and LF are both recognized as record separators by
				// Commons CSV's reader regardless of this setting (which
				// only governs writing); no extra configuration is needed
				// for that requirement.
				.setIgnoreEmptyLines(true)
				.setTrim(false)
				.build();
		try (CSVParser parser = CSVParser.parse(text, format)) {
			return parser.getRecords();
		} catch (IOException | RuntimeException e) {
			// Covers malformed quoting and any other structural CSV
			// parsing failure Commons CSV surfaces -- normalized to one
			// safe, generic message. The underlying file content is never
			// included.
			throw new InvalidSettlementRequestException("file: malformed CSV");
		}
	}

	private void validateHeader(CSVRecord header) {
		List<String> actual = new ArrayList<>(header.size());
		header.forEach(actual::add);
		if (!EXPECTED_HEADER.equals(actual)) {
			throw new InvalidSettlementRequestException(
					"header: must be exactly [" + String.join(",", EXPECTED_HEADER) + "] in this order");
		}
	}

	private SettlementCsvRow parseRow(CSVRecord record, int sourceRowNumber, String normalizedSource,
			SettlementImportProperties properties) {
		if (record.size() != EXPECTED_HEADER.size()) {
			throw new InvalidSettlementRequestException(
					"row " + sourceRowNumber + ": expected " + EXPECTED_HEADER.size() + " columns");
		}

		String externalReference = validateExternalReference(record.get(0), sourceRowNumber, properties);
		UUID transactionId = validateTransactionId(record.get(1), sourceRowNumber);
		BigDecimal amount = validateAmount(record.get(2), sourceRowNumber);
		String currency = validateCurrency(record.get(3), sourceRowNumber);
		Instant settledAt = validateSettledAt(record.get(4), sourceRowNumber);

		String rowHash = RowFingerprint.sha256Hex(normalizedSource, externalReference, transactionId, amount,
				currency, settledAt);

		return new SettlementCsvRow(sourceRowNumber, externalReference, transactionId, amount, currency, settledAt,
				rowHash);
	}

	private String validateExternalReference(String raw, int rowNumber, SettlementImportProperties properties) {
		String trimmed = raw == null ? "" : raw.trim();
		if (trimmed.isEmpty()) {
			throw new InvalidSettlementRequestException("row " + rowNumber + ": external_reference must not be blank");
		}
		if (trimmed.length() > properties.getMaxExternalReferenceLength()) {
			throw new InvalidSettlementRequestException("row " + rowNumber
					+ ": external_reference exceeds maximum length of " + properties.getMaxExternalReferenceLength());
		}
		if (CONTROL_CHARACTER_PATTERN.matcher(trimmed).find()) {
			throw new InvalidSettlementRequestException(
					"row " + rowNumber + ": external_reference must be printable text with no control characters");
		}
		return trimmed;
	}

	private UUID validateTransactionId(String raw, int rowNumber) {
		String trimmed = raw == null ? "" : raw.trim();
		try {
			return UUID.fromString(trimmed);
		} catch (IllegalArgumentException e) {
			throw new InvalidSettlementRequestException("row " + rowNumber + ": transaction_id must be a canonical UUID");
		}
	}

	private BigDecimal validateAmount(String raw, int rowNumber) {
		String trimmed = raw == null ? "" : raw.trim();
		if (!AMOUNT_PATTERN.matcher(trimmed).matches()) {
			throw new InvalidSettlementRequestException("row " + rowNumber
					+ ": amount must be a plain decimal string with exactly two decimal places");
		}
		BigDecimal amount = new BigDecimal(trimmed);
		if (amount.signum() <= 0) {
			throw new InvalidSettlementRequestException("row " + rowNumber + ": amount must be greater than zero");
		}
		return amount;
	}

	private String validateCurrency(String raw, int rowNumber) {
		String trimmed = raw == null ? "" : raw.trim();
		if (!CURRENCY_FORMAT_PATTERN.matcher(trimmed).matches()) {
			throw new InvalidSettlementRequestException("row " + rowNumber + ": currency must be three uppercase letters");
		}
		if (!SUPPORTED_CURRENCIES.contains(trimmed)) {
			throw new InvalidSettlementRequestException("row " + rowNumber + ": unsupported currency");
		}
		return trimmed;
	}

	private Instant validateSettledAt(String raw, int rowNumber) {
		String trimmed = raw == null ? "" : raw.trim();
		try {
			return OffsetDateTime.parse(trimmed, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
		} catch (DateTimeParseException e) {
			throw new InvalidSettlementRequestException(
					"row " + rowNumber + ": settled_at must be an ISO-8601 instant with an explicit UTC offset");
		}
	}

}
