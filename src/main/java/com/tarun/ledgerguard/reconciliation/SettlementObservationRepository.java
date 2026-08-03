package com.tarun.ledgerguard.reconciliation;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Read-only access to {@code settlement_record} for Task 15's own
 * purposes. Deliberately independent of the {@code settlement} package's
 * own (package-private) {@code SettlementRecordRepository} — see
 * {@link SettlementObservation}'s Javadoc. Never writes to
 * {@code settlement_record}.
 */
@Repository
class SettlementObservationRepository {

	private static final RowMapper<SettlementObservation> ROW_MAPPER = (rs, rowNum) -> new SettlementObservation(
			(UUID) rs.getObject("id"),
			(UUID) rs.getObject("transaction_id"),
			rs.getBigDecimal("amount"),
			rs.getString("currency"),
			rs.getInt("source_row_number"));

	private final NamedParameterJdbcTemplate jdbcTemplate;

	SettlementObservationRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Every settlement observation FIRST created by {@code importId} —
	 * see V6's header comment and docs/ARCHITECTURE.md for exactly why
	 * this is {@code first_import_id}, not a broader "every row this
	 * import's file logically contained." Bounded by Task 14's own
	 * 10,000-row import limit; one bulk query, never one per observation.
	 */
	List<SettlementObservation> findByFirstImportId(UUID importId) {
		return jdbcTemplate.query(
				"SELECT id, transaction_id, amount, currency, source_row_number "
						+ "FROM settlement_record WHERE first_import_id = :importId",
				new MapSqlParameterSource().addValue("importId", importId),
				ROW_MAPPER);
	}

}
