package com.tarun.ledgerguard.inbox;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Deliberately implemented with {@link NamedParameterJdbcTemplate} rather
 * than normal JPA persistence (unlike every other repository in this
 * project): a plain JPA {@code save()} that hits the
 * {@code processed_event} primary key would throw a
 * {@code PersistenceException} that marks the whole
 * {@code EntityManager}/transaction rollback-only, making "was this row
 * already claimed" indistinguishable from "the transaction is now
 * unusable" — exactly the exception-driven duplicate control flow the
 * Task 13 contract forbids. {@code INSERT ... ON CONFLICT (event_id) DO
 * NOTHING} plus its affected-row count is a normal, successful statement
 * either way, so {@link #tryClaim} can report which case happened without
 * ever needing to catch a constraint-violation exception for the routine
 * duplicate path.
 *
 * <p>No update or delete method exists here by design — {@code
 * processed_event} is append-only (see {@code V4}'s triggers).
 */
@Repository
public class ProcessedEventRepository {

	private static final RowMapper<ProcessedEventRecord> ROW_MAPPER = (rs, rowNum) -> new ProcessedEventRecord(
			(UUID) rs.getObject("event_id"),
			(UUID) rs.getObject("aggregate_id"),
			rs.getString("event_type"),
			rs.getInt("schema_version"),
			rs.getString("payload_hash"),
			rs.getString("source_topic"),
			rs.getInt("source_partition"),
			rs.getLong("source_offset"),
			rs.getObject("processed_at", OffsetDateTime.class).toInstant());

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public ProcessedEventRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Attempts to atomically claim first-processing rights for
	 * {@code eventId}. Returns {@code true} if this call's row was the one
	 * actually inserted (this caller owns first processing); {@code false}
	 * if a row for this {@code eventId} already existed (a duplicate —
	 * the caller must then compare via {@link #findByEventId}). Correct
	 * under concurrent callers: PostgreSQL resolves the {@code ON
	 * CONFLICT} race, never both callers.
	 *
	 * <p>A source-position (topic/partition/offset) uniqueness violation —
	 * a different {@code eventId} claiming a Kafka position another event
	 * already occupies, which never happens under correct redelivery —
	 * is not caught here; it propagates as a genuine failure, since {@code
	 * ON CONFLICT (event_id)} only suppresses the event-id conflict, not
	 * that unrelated constraint.
	 */
	public boolean tryClaim(UUID eventId, UUID aggregateId, String eventType, int schemaVersion, String payloadHash,
			String sourceTopic, int sourcePartition, long sourceOffset) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("eventId", eventId)
				.addValue("aggregateId", aggregateId)
				.addValue("eventType", eventType)
				.addValue("schemaVersion", schemaVersion)
				.addValue("payloadHash", payloadHash)
				.addValue("sourceTopic", sourceTopic)
				.addValue("sourcePartition", sourcePartition)
				.addValue("sourceOffset", sourceOffset);
		int rowsInserted = jdbcTemplate.update("""
				INSERT INTO processed_event
					(event_id, aggregate_id, event_type, schema_version, payload_hash,
					 source_topic, source_partition, source_offset)
				VALUES
					(:eventId, :aggregateId, :eventType, :schemaVersion, :payloadHash,
					 :sourceTopic, :sourcePartition, :sourceOffset)
				ON CONFLICT (event_id) DO NOTHING
				""", params);
		return rowsInserted == 1;
	}

	public Optional<ProcessedEventRecord> findByEventId(UUID eventId) {
		List<ProcessedEventRecord> results = jdbcTemplate.query(
				"SELECT event_id, aggregate_id, event_type, schema_version, payload_hash, "
						+ "source_topic, source_partition, source_offset, processed_at "
						+ "FROM processed_event WHERE event_id = :eventId",
				new MapSqlParameterSource("eventId", eventId), ROW_MAPPER);
		return results.stream().findFirst();
	}

}
