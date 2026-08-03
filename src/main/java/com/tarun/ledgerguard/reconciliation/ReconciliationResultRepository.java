package com.tarun.ledgerguard.reconciliation;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Implemented with {@link NamedParameterJdbcTemplate}, the same reason as
 * {@link ReconciliationRunRepository}. No update or delete method exists
 * here by design — {@code reconciliation_result} is append-only (see
 * {@code V6}'s triggers).
 */
@Repository
class ReconciliationResultRepository {

	private static final RowMapper<StoredReconciliationResult> ROW_MAPPER = (rs, rowNum) -> new StoredReconciliationResult(
			(UUID) rs.getObject("id"),
			(UUID) rs.getObject("run_id"),
			(UUID) rs.getObject("settlement_record_id"),
			(UUID) rs.getObject("reported_transaction_id"),
			ReconciliationOutcome.valueOf(rs.getString("outcome")),
			rs.getBigDecimal("reported_amount"),
			rs.getString("reported_currency"),
			rs.getBigDecimal("internal_amount"),
			rs.getString("internal_currency"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private final NamedParameterJdbcTemplate jdbcTemplate;

	ReconciliationResultRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Inserts every result of a newly committed run in one batch
	 * statement — never one INSERT per settlement observation. Safe
	 * without an {@code ON CONFLICT} clause: this is only ever called
	 * immediately after this same transaction exclusively claimed the
	 * owning {@code reconciliation_run} row (see
	 * {@code ReconciliationProcessor}), so no concurrent writer can ever
	 * be inserting results for the same {@code run_id}.
	 */
	void insertAll(List<NewResult> results) {
		if (results.isEmpty()) {
			return;
		}
		MapSqlParameterSource[] batchParams = results.stream()
				.map(result -> new MapSqlParameterSource()
						.addValue("id", result.id())
						.addValue("runId", result.runId())
						.addValue("settlementRecordId", result.settlementRecordId())
						.addValue("reportedTransactionId", result.reportedTransactionId())
						.addValue("outcome", result.outcome().name())
						.addValue("reportedAmount", result.reportedAmount())
						.addValue("reportedCurrency", result.reportedCurrency())
						.addValue("internalAmount", result.internalAmount(), java.sql.Types.NUMERIC)
						.addValue("internalCurrency", result.internalCurrency(), java.sql.Types.VARCHAR))
				.toArray(MapSqlParameterSource[]::new);
		jdbcTemplate.batchUpdate("""
				INSERT INTO reconciliation_result
					(id, run_id, settlement_record_id, reported_transaction_id, outcome, reported_amount,
					 reported_currency, internal_amount, internal_currency)
				VALUES
					(:id, :runId, :settlementRecordId, :reportedTransactionId, :outcome, :reportedAmount,
					 :reportedCurrency, :internalAmount, :internalCurrency)
				""", batchParams);
	}

	/**
	 * One page of a run's results, in a stable, deterministic order: the
	 * originating settlement observation's {@code source_row_number} —
	 * the row's position in the settlement CSV file that first created
	 * it, unique within one import and therefore a complete, meaningful
	 * ordering for one run's results (every result in a run belongs to
	 * exactly one import). The join's cost is bounded by the run's own
	 * result count, which Task 14's 10,000-row import limit already caps.
	 */
	List<StoredReconciliationResult> findByRunId(UUID runId, int limit, int offset) {
		return jdbcTemplate.query("""
				SELECT rr.id, rr.run_id, rr.settlement_record_id, rr.reported_transaction_id, rr.outcome,
				       rr.reported_amount, rr.reported_currency, rr.internal_amount, rr.internal_currency,
				       rr.created_at
				FROM reconciliation_result rr
				JOIN settlement_record sr ON sr.id = rr.settlement_record_id
				WHERE rr.run_id = :runId
				ORDER BY sr.source_row_number ASC
				LIMIT :limit OFFSET :offset
				""",
				new MapSqlParameterSource()
						.addValue("runId", runId)
						.addValue("limit", limit)
						.addValue("offset", offset),
				ROW_MAPPER);
	}

	record NewResult(UUID id, UUID runId, UUID settlementRecordId, UUID reportedTransactionId,
			ReconciliationOutcome outcome, BigDecimal reportedAmount, String reportedCurrency,
			BigDecimal internalAmount, String internalCurrency) {
	}

}
