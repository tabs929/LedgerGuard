package com.tarun.ledgerguard.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

	/**
	 * Candidate discovery only (Task 12): the deterministic, bounded set of
	 * currently-pending event ids, oldest first — {@code created_at ASC,
	 * id ASC}, backed directly by the V3 partial pending index
	 * ({@code idx_outbox_event_pending ... WHERE published_at IS NULL}).
	 * Reads ids only, never the payload, and takes no lock — the
	 * transactional worker re-checks and locks each candidate individually
	 * (see {@link #lockPendingById}) before ever publishing it, since a
	 * row's pending status can change between this read and that lock
	 * attempt.
	 */
	@Query(value = "SELECT id FROM outbox_event WHERE published_at IS NULL "
			+ "ORDER BY created_at ASC, id ASC LIMIT :limit", nativeQuery = true)
	List<UUID> findPendingCandidateIds(@Param("limit") int limit);

	/**
	 * Attempts to claim exactly one still-pending row for publication,
	 * within the caller's own transaction. {@code FOR UPDATE SKIP LOCKED}
	 * is what makes this safe across multiple concurrent publishers (in
	 * one instance or across many): a row already locked by another
	 * in-flight publication is silently excluded from the result set
	 * (never blocked on), so a concurrent worker racing for the same
	 * candidate gets an empty {@link Optional} immediately rather than
	 * waiting — see {@code outbox.OutboxPublisher} and
	 * docs/ARCHITECTURE.md's "Kafka Publishing" section for the full
	 * reasoning. Also excludes a row that has already been published
	 * (by this or an earlier transaction) via {@code published_at IS
	 * NULL} in the same query, so an already-published row is never
	 * re-claimed during normal operation.
	 */
	@Query(value = "SELECT * FROM outbox_event WHERE id = :id AND published_at IS NULL "
			+ "FOR UPDATE SKIP LOCKED", nativeQuery = true)
	Optional<OutboxEvent> lockPendingById(@Param("id") UUID id);

}
