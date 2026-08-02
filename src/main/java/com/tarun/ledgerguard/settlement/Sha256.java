package com.tarun.ledgerguard.settlement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Lowercase hexadecimal SHA-256, used for both the exact-file hash
 * ({@code settlement_import.file_hash}, over raw uploaded bytes) and the
 * canonical row fingerprint ({@code settlement_record.row_hash}, over
 * {@link RowFingerprint}'s UTF-8 canonical string). Every SHA-256
 * algorithm lookup in this JVM succeeds (it is a mandatory JDK algorithm),
 * so {@link NoSuchAlgorithmException} is wrapped as unchecked rather than
 * propagated as a checked exception callers would have no real recovery
 * from.
 */
final class Sha256 {

	private Sha256() {
	}

	static String hex(byte[] data) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(data);
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(Character.forDigit((b >> 4) & 0xF, 16));
				hex.append(Character.forDigit(b & 0xF, 16));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 algorithm not available", e);
		}
	}

	static String hex(String utf8) {
		return hex(utf8.getBytes(StandardCharsets.UTF_8));
	}

}
