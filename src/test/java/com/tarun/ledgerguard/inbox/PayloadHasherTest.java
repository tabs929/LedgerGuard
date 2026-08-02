package com.tarun.ledgerguard.inbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PayloadHasherTest {

	@Test
	void hashesTheExactUtf8BytesOfTheGivenString() {
		// Known SHA-256("hello") value -- confirms the exact algorithm and
		// encoding, not just internal self-consistency.
		String hash = PayloadHasher.sha256Hex("hello");
		assertThat(hash).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
	}

	@Test
	void hashIsLowercaseHexOf64Characters() {
		String hash = PayloadHasher.sha256Hex("{\"eventId\":\"11111111-1111-1111-1111-111111111111\"}");
		assertThat(hash).matches("^[0-9a-f]{64}$");
	}

	@Test
	void identicalStringsProduceIdenticalHashes() {
		String value = "{\"a\":1,\"b\":2}";
		assertThat(PayloadHasher.sha256Hex(value)).isEqualTo(PayloadHasher.sha256Hex(value));
	}

	@Test
	void differentStringsProduceDifferentHashes() {
		assertThat(PayloadHasher.sha256Hex("{\"amount\":\"100.0000\"}"))
				.isNotEqualTo(PayloadHasher.sha256Hex("{\"amount\":\"200.0000\"}"));
	}

	@Test
	void whitespaceDifferencesProduceDifferentHashes() {
		// Proves the hash is over the exact raw string, not a
		// semantically-equivalent reserialized form.
		assertThat(PayloadHasher.sha256Hex("{\"a\":1}")).isNotEqualTo(PayloadHasher.sha256Hex("{\"a\": 1}"));
	}

}
