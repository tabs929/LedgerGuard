package com.tarun.ledgerguard.settlement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Builds the deterministic canonical representation of one settlement
 * observation and its SHA-256 fingerprint ({@code settlement_record.row_hash}),
 * per docs/DATA_MODEL.md's "Settlement Row Identity" section.
 *
 * <p>Fields are encoded length-prefixed ({@code <utf8-byte-length>:<value>},
 * one after another, in a fixed order) rather than joined with a plain
 * delimiter such as a comma. A naive delimiter-joined string is ambiguous
 * -- e.g. external_reference {@code "a,b"} joined with transaction_id
 * {@code "c"} is indistinguishable from external_reference {@code "a"}
 * joined with transaction_id {@code "b,c"} -- and CSV field values are
 * exactly the kind of untrusted, delimiter-containing input this must be
 * collision-resistant against. A length prefix makes every field's
 * boundary unambiguous regardless of its content, so no escaping scheme
 * is needed and no two distinct field tuples can ever produce the same
 * canonical string.
 */
final class RowFingerprint {

	private RowFingerprint() {
	}

	static String canonicalRepresentation(String normalizedSource, String externalReference, UUID transactionId,
			BigDecimal amount, String currency, Instant settledAt) {
		StringBuilder canonical = new StringBuilder();
		appendField(canonical, normalizedSource);
		appendField(canonical, externalReference);
		appendField(canonical, transactionId.toString());
		appendField(canonical, amount.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
		appendField(canonical, currency);
		appendField(canonical, DateTimeFormatter.ISO_INSTANT.format(settledAt));
		return canonical.toString();
	}

	static String sha256Hex(String normalizedSource, String externalReference, UUID transactionId,
			BigDecimal amount, String currency, Instant settledAt) {
		return Sha256.hex(canonicalRepresentation(normalizedSource, externalReference, transactionId, amount,
				currency, settledAt));
	}

	private static void appendField(StringBuilder canonical, String value) {
		int byteLength = value.getBytes(StandardCharsets.UTF_8).length;
		canonical.append(byteLength).append(':').append(value);
	}

}
