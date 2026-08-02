package com.tarun.ledgerguard.settlement;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class Sha256Test {

	@Test
	void hashesTheExactUtf8BytesOfAString() {
		// Known SHA-256("hello") value.
		assertThat(Sha256.hex("hello")).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
	}

	@Test
	void hashIsLowercaseHexOf64Characters() {
		assertThat(Sha256.hex("external_reference,transaction_id,amount,currency,settled_at")).matches("^[0-9a-f]{64}$");
	}

	@Test
	void identicalBytesProduceIdenticalHashes() {
		byte[] bytes = "same content".getBytes(StandardCharsets.UTF_8);
		assertThat(Sha256.hex(bytes)).isEqualTo(Sha256.hex(bytes.clone()));
	}

	@Test
	void byteDistinctFilesProduceDifferentHashes() {
		byte[] withoutBom = "external_reference,transaction_id,amount,currency,settled_at\n".getBytes(StandardCharsets.UTF_8);
		byte[] utf8Bom = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
		byte[] withBom = new byte[utf8Bom.length + withoutBom.length];
		System.arraycopy(utf8Bom, 0, withBom, 0, utf8Bom.length);
		System.arraycopy(withoutBom, 0, withBom, utf8Bom.length, withoutBom.length);

		// A BOM-prefixed file and its BOM-stripped equivalent must hash
		// differently -- file_hash is computed over the exact original
		// bytes, before any BOM removal (see
		// SettlementImportService#importFile).
		assertThat(Sha256.hex(withBom)).isNotEqualTo(Sha256.hex(withoutBom));
	}

}
