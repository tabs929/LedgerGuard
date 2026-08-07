package com.tarun.ledgerguard.idempotency;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * The canonical, normalized representation of a deposit or transfer
 * command, used both to detect a conflicting reuse of an Idempotency-Key
 * and to populate the comparable columns of {@code idempotency_key}.
 *
 * <p>{@code amount} and {@code currency} must already be normalized by the
 * caller exactly as {@code DepositService}/{@code TransferService}
 * normalize them for the operation itself (currency uppercased via
 * {@link java.util.Locale#ROOT}, amount scaled to 4 decimal places) —
 * this is what makes {@code "100"}, {@code "100.0"}, and {@code "100.00"}
 * compare as the same command regardless of how the client formatted the
 * original request.
 *
 * <p>{@link #matches(IdempotencyCommand)} is the exact, canonical
 * comparison the Task 10 contract requires — {@link #sha256Hex()} is
 * computed for defense-in-depth storage only ({@code
 * idempotency_key.command_hash}) and is never relied on alone.
 */
public record IdempotencyCommand(
		IdempotencyOperationType operationType,
		UUID primaryAccountId,
		UUID secondaryAccountId,
		BigDecimal amount,
		String currency) {

	public static IdempotencyCommand forDeposit(UUID destinationAccountId, BigDecimal amount, String currency) {
		return new IdempotencyCommand(IdempotencyOperationType.DEPOSIT, destinationAccountId, null, amount, currency);
	}

	public static IdempotencyCommand forTransfer(UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount,
			String currency) {
		return new IdempotencyCommand(IdempotencyOperationType.TRANSFER, sourceAccountId, destinationAccountId,
				amount, currency);
	}

	public boolean matches(IdempotencyCommand other) {
		return operationType == other.operationType
				&& primaryAccountId.equals(other.primaryAccountId)
				&& Objects.equals(secondaryAccountId, other.secondaryAccountId)
				&& amount.compareTo(other.amount) == 0
				&& currency.equals(other.currency);
	}

	private String canonicalString() {
		return operationType + "|" + primaryAccountId + "|" + (secondaryAccountId == null ? "" : secondaryAccountId)
				+ "|" + amount.toPlainString() + "|" + currency;
	}

	public String sha256Hex() {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest(canonicalString().getBytes(StandardCharsets.UTF_8));
			StringBuilder hex = new StringBuilder(hash.length * 2);
			for (byte b : hash) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (NoSuchAlgorithmException e) {
			// SHA-256 is a mandatory JDK algorithm (JLS/JCA baseline) -- this
			// can never actually happen on any conforming JVM.
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	/**
	 * A stable 64-bit id derived from the raw Idempotency-Key header value
	 * together with the authenticated principal's subject (Task 17) -- not
	 * this command's fields -- for use as the {@code
	 * pg_advisory_xact_lock(bigint)} argument that serializes concurrent
	 * requests bearing the same key from the same principal. Including the
	 * principal means two different customers who happen to choose the
	 * same literal key string never unnecessarily serialize against each
	 * other. A collision between two different (principal, key) pairs
	 * would only cause them to serialize unnecessarily -- correctness
	 * never depends on this id alone, since the exact (principal, key)
	 * pair is always compared afterward.
	 */
	public static long advisoryLockId(String principalSubject, String idempotencyKey) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			byte[] hash = digest.digest((principalSubject + ":" + idempotencyKey).getBytes(StandardCharsets.UTF_8));
			long id = 0L;
			for (int i = 0; i < 8; i++) {
				id = (id << 8) | (hash[i] & 0xFF);
			}
			return id;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

}
