package com.tarun.ledgerguard.reconciliation;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implemented with {@link NamedParameterJdbcTemplate} rather than JPA, the
 * same deliberate choice as every other append-only claim table in this
 * project ({@code ProcessedEventRepository}, {@code SettlementImportRepository}):
 * {@code INSERT ... ON CONFLICT DO NOTHING} plus its {@code RETURNING}
 * result is a normal, successful statement either way, so
 * {@code ReconciliationProcessor} can distinguish "this call's run won the
 * claim" from "a concurrent transaction already holds this identity"
 * without ever needing to catch a constraint-violation exception for the
 * routine replay path. No update or delete method exists here by design —
 * {@code reconciliation_run} is append-only (see {@code V6}'s triggers).
 */
@Repository
class ReconciliationRunRepository {

	private static final RowMapper<StoredReconciliationRun> ROW_MAPPER = (rs, rowNum) -> new StoredReconciliationRun(
			(UUID) rs.getObject("id"),
			(UUID) rs.getObject("settlement_import_id"),
			rs.getInt("algorithm_version"),
			rs.getInt("total_result_count"),
			rs.getInt("matched_count"),
			rs.getInt("discrepancy_count"),
			rs.getInt("inconsistent_count"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private static final String SELECT_COLUMNS = "id, settlement_import_id, algorithm_version, "
			+ "total_result_count, matched_count, discrepancy_count, inconsistent_count, created_at";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	ReconciliationRunRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	Optional<StoredReconciliationRun> findBySettlementImportIdAndAlgorithmVersion(UUID settlementImportId,
			int algorithmVersion) {
		List<StoredReconciliationRun> results = jdbcTemplate.query(
				"SELECT " + SELECT_COLUMNS + " FROM reconciliation_run "
						+ "WHERE settlement_import_id = :settlementImportId AND algorithm_version = :algorithmVersion",
				new MapSqlParameterSource()
						.addValue("settlementImportId", settlementImportId)
						.addValue("algorithmVersion", algorithmVersion),
				ROW_MAPPER);
		return results.stream().findFirst();
	}

	/**
	 * The latest (highest algorithm version) committed run for an import,
	 * if any — used by the read-only {@code GET .../reconciliation}
	 * endpoint. Task 15 only ever writes {@code algorithm_version = 1}, so
	 * today this is equivalent to "the one run for this import," but the
	 * query itself does not assume that, so a future approved algorithm
	 * version requires no change here.
	 */
	Optional<StoredReconciliationRun> findLatestBySettlementImportId(UUID settlementImportId) {
		List<StoredReconciliationRun> results = jdbcTemplate.query(
				"SELECT " + SELECT_COLUMNS + " FROM reconciliation_run "
						+ "WHERE settlement_import_id = :settlementImportId "
						+ "ORDER BY algorithm_version DESC LIMIT 1",
				new MapSqlParameterSource().addValue("settlementImportId", settlementImportId),
				ROW_MAPPER);
		return results.stream().findFirst();
	}

	/**
	 * Atomically claims the (settlement_import_id, algorithm_version)
	 * identity, inserting the row with its final result counts in one
	 * statement — reconciliation_run is append-only, so it is never
	 * inserted with placeholder counts and updated afterward. Returns the
	 * persisted row (including the database-assigned {@code created_at})
	 * if this call's row was the one actually inserted, or
	 * {@link Optional#empty()} if a concurrent transaction already
	 * committed this identity.
	 */
	Optional<StoredReconciliationRun> tryInsert(UUID id, UUID settlementImportId, int algorithmVersion,
			int totalResultCount, int matchedCount, int discrepancyCount, int inconsistentCount) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("id", id)
				.addValue("settlementImportId", settlementImportId)
				.addValue("algorithmVersion", algorithmVersion)
				.addValue("totalResultCount", totalResultCount)
				.addValue("matchedCount", matchedCount)
				.addValue("discrepancyCount", discrepancyCount)
				.addValue("inconsistentCount", inconsistentCount);
		List<StoredReconciliationRun> inserted = jdbcTemplate.query("""
				INSERT INTO reconciliation_run
					(id, settlement_import_id, algorithm_version, total_result_count, matched_count,
					 discrepancy_count, inconsistent_count)
				VALUES
					(:id, :settlementImportId, :algorithmVersion, :totalResultCount, :matchedCount,
					 :discrepancyCount, :inconsistentCount)
				ON CONFLICT (settlement_import_id, algorithm_version) DO NOTHING
				RETURNING """ + " " + SELECT_COLUMNS, params, ROW_MAPPER);
		return inserted.stream().findFirst();
	}

}
