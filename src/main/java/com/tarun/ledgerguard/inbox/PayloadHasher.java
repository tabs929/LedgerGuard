package com.tarun.ledgerguard.inbox;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * SHA-256 over the exact UTF-8 bytes of a Kafka value string — computed
 * directly from the raw string, never by deserializing and reserializing
 * it, so the fingerprint is unaffected by JSON key ordering or
 * whitespace differences a round-trip could introduce. A small, focused
 * utility purely so this can be unit tested in isolation from
 * {@link LedgerEventProcessor}'s PostgreSQL-backed behavior.
 */
final class PayloadHasher {

	private PayloadHasher() {
	}

	static String sha256Hex(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		}
		catch (NoSuchAlgorithmException e) {
			// SHA-256 is a mandatory JDK algorithm -- this can never
			// actually happen on any conforming JVM.
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

}
