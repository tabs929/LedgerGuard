package com.tarun.ledgerguard.idempotency;

import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Makes a deposit or transfer command idempotent, keyed by the caller's
 * Idempotency-Key header value. Called from inside
 * {@code DepositService.deposit(...)}/{@code TransferService.transfer(...)}
 * — always within their ambient {@code @Transactional} method (default
 * REQUIRES propagation), never a nested {@code REQUIRES_NEW} transaction —
 * so the financial write and the idempotency record commit or roll back
 * together, atomically, as one unit.
 *
 * <p><b>Concurrency:</b> before touching {@code idempotency_key} or any
 * account row, this acquires a PostgreSQL transaction-scoped advisory lock
 * ({@code pg_advisory_xact_lock}) keyed on a hash of the authenticated
 * principal's subject together with the raw Idempotency-Key string (see
 * {@link IdempotencyCommand#advisoryLockId}) -- Task 17 scopes this per
 * principal so two different customers never serialize against each other
 * merely for choosing the same literal key string.
 * A concurrent request bearing the *same* (principal, key) pair blocks inside that call
 * until the first transaction ends — commit or rollback, either way the
 * lock releases automatically since it is transaction-scoped. This is a
 * database-level lock, not an in-memory one, so it is correct across
 * multiple application instances without any JVM-local coordination.
 * Once the lock is granted, a plain {@code SELECT} (no {@code FOR UPDATE}
 * needed — the advisory lock already serializes same-key access)
 * distinguishes replay (canonical match — return the stored response
 * verbatim, no new writes) from conflict (mismatch — throw, no financial
 * work attempted) from a genuinely new key (no row — perform the
 * operation, then persist the claim as the transaction's last statement).
 * If the operation throws, the method exits before that final persist is
 * reached, so the key is never consumed by a failed attempt and a
 * corrected retry with the same key starts fresh.
 *
 * <p>The advisory lock is always acquired before this class (or its
 * caller) takes any {@code SELECT ... FOR UPDATE} account-row lock, in
 * every code path that uses it — so it cannot introduce a new deadlock
 * cycle with the existing deterministic ascending-account-id lock
 * ordering in {@code AccountRepository}. Different keys hash to
 * independent lock ids, so unrelated requests never serialize against
 * each other.
 */
@Component
public class IdempotencyService {

	private static final int SUCCESS_STATUS = 201;

	private final IdempotencyKeyRepository repository;
	private final EntityManager entityManager;
	private final ObjectMapper objectMapper;

	public IdempotencyService(IdempotencyKeyRepository repository, EntityManager entityManager,
			ObjectMapper objectMapper) {
		this.repository = repository;
		this.entityManager = entityManager;
		this.objectMapper = objectMapper;
	}

	public <T> T execute(String idempotencyKey, String principalSubject, IdempotencyCommand command,
			Class<T> responseType, Function<T, UUID> transactionIdExtractor, Supplier<T> operation) {
		entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:lockId)")
				.setParameter("lockId", IdempotencyCommand.advisoryLockId(principalSubject, idempotencyKey))
				.getSingleResult();

		Optional<IdempotencyKeyRecord> existing =
				repository.findByPrincipalSubjectAndIdempotencyKey(principalSubject, idempotencyKey);
		if (existing.isPresent()) {
			IdempotencyKeyRecord record = existing.get();
			if (!record.matches(command)) {
				throw new IdempotencyConflictException(idempotencyKey);
			}
			return deserialize(record.getResponseBody(), responseType, idempotencyKey);
		}

		T response = operation.get();

		IdempotencyKeyRecord record = new IdempotencyKeyRecord(idempotencyKey, principalSubject, command,
				transactionIdExtractor.apply(response), SUCCESS_STATUS, serialize(response, idempotencyKey));
		repository.save(record);
		// Flush now so a UNIQUE-constraint violation on idempotency_key (the
		// defense-in-depth backstop -- the advisory lock above already
		// prevents this in practice) surfaces here, inside this transaction,
		// causing the whole method (financial write included) to roll back
		// together, rather than at some later unrelated flush point.
		repository.flush();

		return response;
	}

	private <T> String serialize(T response, String idempotencyKey) {
		try {
			return objectMapper.writeValueAsString(response);
		} catch (JacksonException e) {
			throw new IllegalStateException("Failed to serialize idempotent response for key " + idempotencyKey, e);
		}
	}

	private <T> T deserialize(String json, Class<T> responseType, String idempotencyKey) {
		try {
			return objectMapper.readValue(json, responseType);
		} catch (JacksonException e) {
			throw new IllegalStateException("Failed to deserialize stored idempotent response for key "
					+ idempotencyKey, e);
		}
	}

}
