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
 * Read-only access to {@code settlement_import} for Task 15's own
 * purposes (existence checks, and the row counts needed for its response
 * summary). Deliberately a separate, independent, read-only repository
 * rather than a dependency on the {@code settlement} package's own
 * (package-private) {@code SettlementImportRepository} — see
 * {@link SettlementImportSummary}'s Javadoc. Never writes to
 * {@code settlement_import}.
 */
@Repository
class SettlementImportSummaryRepository {

	private static final RowMapper<SettlementImportSummary> ROW_MAPPER = (rs, rowNum) -> new SettlementImportSummary(
			(UUID) rs.getObject("id"),
			rs.getInt("total_row_count"),
			rs.getInt("inserted_row_count"),
			rs.getInt("duplicate_row_count"),
			rs.getObject("imported_at", OffsetDateTime.class).toInstant());

	private final NamedParameterJdbcTemplate jdbcTemplate;

	SettlementImportSummaryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	Optional<SettlementImportSummary> findById(UUID id) {
		List<SettlementImportSummary> results = jdbcTemplate.query(
				"SELECT id, total_row_count, inserted_row_count, duplicate_row_count, imported_at "
						+ "FROM settlement_import WHERE id = :id",
				new MapSqlParameterSource().addValue("id", id),
				ROW_MAPPER);
		return results.stream().findFirst();
	}

}
