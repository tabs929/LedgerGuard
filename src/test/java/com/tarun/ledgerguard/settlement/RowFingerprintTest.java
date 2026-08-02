package com.tarun.ledgerguard.settlement;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RowFingerprintTest {

	private static final UUID TXN_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
	private static final Instant SETTLED_AT = Instant.parse("2026-07-15T10:00:00Z");

	@Test
	void isDeterministicForIdenticalInputs() {
		String first = RowFingerprint.sha256Hex("acme-bank", "EXT-001", TXN_ID, new BigDecimal("100.00"), "USD", SETTLED_AT);
		String second = RowFingerprint.sha256Hex("acme-bank", "EXT-001", TXN_ID, new BigDecimal("100.00"), "USD", SETTLED_AT);
		assertThat(first).isEqualTo(second);
	}

	@Test
	void isLowercaseHexOf64Characters() {
		String hash = RowFingerprint.sha256Hex("acme-bank", "EXT-001", TXN_ID, new BigDecimal("100.00"), "USD", SETTLED_AT);
		assertThat(hash).matches("^[0-9a-f]{64}$");
	}

	@Test
	void differsWhenAnySingleFieldDiffers() {
		String base = RowFingerprint.sha256Hex("acme-bank", "EXT-001", TXN_ID, new BigDecimal("100.00"), "USD", SETTLED_AT);

		assertThat(RowFingerprint.sha256Hex("other-bank", "EXT-001", TXN_ID, new BigDecimal("100.00"), "USD", SETTLED_AT))
				.isNotEqualTo(base);
		assertThat(RowFingerprint.sha256Hex("acme-bank", "EXT-002", TXN_ID, new BigDecimal("100.00"), "USD", SETTLED_AT))
				.isNotEqualTo(base);
		assertThat(RowFingerprint.sha256Hex("acme-bank", "EXT-001", UUID.randomUUID(), new BigDecimal("100.00"), "USD", SETTLED_AT))
				.isNotEqualTo(base);
		assertThat(RowFingerprint.sha256Hex("acme-bank", "EXT-001", TXN_ID, new BigDecimal("200.00"), "USD", SETTLED_AT))
				.isNotEqualTo(base);
		assertThat(RowFingerprint.sha256Hex("acme-bank", "EXT-001", TXN_ID, new BigDecimal("100.00"), "EUR", SETTLED_AT))
				.isNotEqualTo(base);
		assertThat(RowFingerprint.sha256Hex("acme-bank", "EXT-001", TXN_ID, new BigDecimal("100.00"), "USD", SETTLED_AT.plusSeconds(1)))
				.isNotEqualTo(base);
	}

	@Test
	void isResistantToFieldBoundaryAmbiguity() {
		// Without length-prefixing, concatenating externalReference="a,b"
		// with transactionId-string-derived content could collide with
		// externalReference="a" + something starting with ",b". The
		// length-prefixed canonical representation must keep these
		// tuples' hashes distinct despite their naive concatenations
		// being identical strings.
		String tupleOne = RowFingerprint.canonicalRepresentation("src", "a,b", TXN_ID, new BigDecimal("1.00"), "USD", SETTLED_AT);
		String tupleTwo = RowFingerprint.canonicalRepresentation("src", "a", TXN_ID, new BigDecimal("1.00"), "USD", SETTLED_AT)
				+ ",b-does-not-belong-to-external-reference";
		assertThat(tupleOne).isNotEqualTo(tupleTwo);
	}

	@Test
	void canonicalRepresentationIsLengthPrefixedPerField() {
		String canonical = RowFingerprint.canonicalRepresentation("src", "ref", TXN_ID, new BigDecimal("1.00"), "USD", SETTLED_AT);
		assertThat(canonical).startsWith("3:src3:ref36:" + TXN_ID);
	}

}
