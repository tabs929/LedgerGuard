package com.tarun.ledgerguard.settlement;

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
 * same deliberate choice as {@code inbox.ProcessedEventRepository} (Task
 * 13): {@code INSERT ... ON CONFLICT DO NOTHING} plus its affected-row
 * count (or, here, its {@code RETURNING} result) is a normal, successful
 * statement either way, so callers can distinguish "this call's row won
 * the claim" from "a concurrent transaction already holds this identity"
 * without ever needing to catch a constraint-violation exception for the
 * routine duplicate/replay path -- a JPA {@code save()} hitting the unique
 * constraint would instead throw a {@code PersistenceException} that
 * marks the whole {@code EntityManager}/transaction rollback-only.
 *
 * <p>No update or delete method exists here by design --
 * {@code settlement_import} is append-only (see {@code V5}'s triggers).
 */
@Repository
class SettlementImportRepository {

	private static final RowMapper<StoredSettlementImport> ROW_MAPPER = (rs, rowNum) -> new StoredSettlementImport(
			(UUID) rs.getObject("id"),
			rs.getString("source"),
			rs.getString("normalized_source"),
			rs.getString("original_filename"),
			rs.getString("file_hash"),
			rs.getLong("file_size_bytes"),
			rs.getInt("total_row_count"),
			rs.getInt("inserted_row_count"),
			rs.getInt("duplicate_row_count"),
			rs.getObject("imported_at", OffsetDateTime.class).toInstant());

	private static final String SELECT_COLUMNS = "id, source, normalized_source, original_filename, file_hash, "
			+ "file_size_bytes, total_row_count, inserted_row_count, duplicate_row_count, imported_at";

	private final NamedParameterJdbcTemplate jdbcTemplate;

	SettlementImportRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	/**
	 * Fast-path lookup only -- see {@code SettlementImportProcessor}'s
	 * usage for why this is never relied on for correctness under
	 * concurrency (the atomic claim in {@link #tryInsert} is what actually
	 * arbitrates a race).
	 */
	Optional<StoredSettlementImport> findByNormalizedSourceAndFileHash(String normalizedSource, String fileHash) {
		List<StoredSettlementImport> results = jdbcTemplate.query(
				"SELECT " + SELECT_COLUMNS + " FROM settlement_import "
						+ "WHERE normalized_source = :normalizedSource AND file_hash = :fileHash",
				new MapSqlParameterSource()
						.addValue("normalizedSource", normalizedSource)
						.addValue("fileHash", fileHash),
				ROW_MAPPER);
		return results.stream().findFirst();
	}

	/**
	 * Atomically claims the (normalized_source, file_hash) identity,
	 * inserting the row with its final row counts in one statement --
	 * settlement_import is append-only, so it is never inserted with
	 * placeholder counts and updated afterward. Returns the persisted row
	 * (including the database-assigned {@code imported_at}) if this
	 * call's row was the one actually inserted, or {@link Optional#empty()}
	 * if a concurrent transaction already committed this identity.
	 */
	Optional<StoredSettlementImport> tryInsert(UUID id, String source, String normalizedSource,
			String originalFilename, String fileHash, long fileSizeBytes, int totalRowCount, int insertedRowCount,
			int duplicateRowCount) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("id", id)
				.addValue("source", source)
				.addValue("normalizedSource", normalizedSource)
				// Explicit VARCHAR type: originalFilename is legitimately
				// null (a multipart part need not carry a filename), and
				// the PostgreSQL JDBC driver cannot always infer a bind
				// parameter's type from a null Java value alone.
				.addValue("originalFilename", originalFilename, java.sql.Types.VARCHAR)
				.addValue("fileHash", fileHash)
				.addValue("fileSizeBytes", fileSizeBytes)
				.addValue("totalRowCount", totalRowCount)
				.addValue("insertedRowCount", insertedRowCount)
				.addValue("duplicateRowCount", duplicateRowCount);
		List<StoredSettlementImport> inserted = jdbcTemplate.query("""
				INSERT INTO settlement_import
					(id, source, normalized_source, original_filename, file_hash, file_size_bytes,
					 total_row_count, inserted_row_count, duplicate_row_count)
				VALUES
					(:id, :source, :normalizedSource, :originalFilename, :fileHash, :fileSizeBytes,
					 :totalRowCount, :insertedRowCount, :duplicateRowCount)
				ON CONFLICT (normalized_source, file_hash) DO NOTHING
				RETURNING """ + " " + SELECT_COLUMNS, params, ROW_MAPPER);
		return inserted.stream().findFirst();
	}

}
