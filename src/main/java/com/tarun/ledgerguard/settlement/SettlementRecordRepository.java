package com.tarun.ledgerguard.settlement;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implemented with {@link NamedParameterJdbcTemplate} for the same reason
 * as {@link SettlementImportRepository} -- see its Javadoc. No update or
 * delete method exists here by design -- {@code settlement_record} is
 * append-only (see {@code V5}'s triggers).
 */
@Repository
class SettlementRecordRepository {

	private static final RowMapper<StoredSettlementRecord> ROW_MAPPER = (rs, rowNum) -> new StoredSettlementRecord(
			(UUID) rs.getObject("id"),
			rs.getString("normalized_source"),
			rs.getString("external_reference"),
			(UUID) rs.getObject("transaction_id"),
			rs.getBigDecimal("amount"),
			rs.getString("currency"),
			rs.getObject("settled_at", OffsetDateTime.class).toInstant(),
			rs.getString("row_hash"),
			(UUID) rs.getObject("first_import_id"),
			rs.getInt("source_row_number"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant());

	private final NamedParameterJdbcTemplate jdbcTemplate;

	SettlementRecordRepository(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	Optional<StoredSettlementRecord> findByNormalizedSourceAndExternalReference(String normalizedSource,
			String externalReference) {
		List<StoredSettlementRecord> results = jdbcTemplate.query(
				"SELECT id, normalized_source, external_reference, transaction_id, amount, currency, "
						+ "settled_at, row_hash, first_import_id, source_row_number, created_at "
						+ "FROM settlement_record "
						+ "WHERE normalized_source = :normalizedSource AND external_reference = :externalReference",
				new MapSqlParameterSource()
						.addValue("normalizedSource", normalizedSource)
						.addValue("externalReference", externalReference),
				ROW_MAPPER);
		return results.stream().findFirst();
	}

	/**
	 * Atomically claims the (normalized_source, external_reference)
	 * identity for one settlement observation. Returns {@code true} if
	 * this call's row was the one actually inserted; {@code false} if an
	 * identity for this (source, reference) pair already existed -- the
	 * caller must then compare via
	 * {@link #findByNormalizedSourceAndExternalReference} to classify the
	 * situation as an identical duplicate or a conflict.
	 */
	boolean tryClaim(UUID id, String normalizedSource, String externalReference, UUID transactionId,
			BigDecimal amount, String currency, Instant settledAt, String rowHash, UUID firstImportId,
			int sourceRowNumber) {
		MapSqlParameterSource params = new MapSqlParameterSource()
				.addValue("id", id)
				.addValue("normalizedSource", normalizedSource)
				.addValue("externalReference", externalReference)
				.addValue("transactionId", transactionId)
				.addValue("amount", amount)
				.addValue("currency", currency)
				.addValue("settledAt", settledAt.atOffset(ZoneOffset.UTC))
				.addValue("rowHash", rowHash)
				.addValue("firstImportId", firstImportId)
				.addValue("sourceRowNumber", sourceRowNumber);
		int rowsInserted = jdbcTemplate.update("""
				INSERT INTO settlement_record
					(id, normalized_source, external_reference, transaction_id, amount, currency,
					 settled_at, row_hash, first_import_id, source_row_number)
				VALUES
					(:id, :normalizedSource, :externalReference, :transactionId, :amount, :currency,
					 :settledAt, :rowHash, :firstImportId, :sourceRowNumber)
				ON CONFLICT (normalized_source, external_reference) DO NOTHING
				""", params);
		return rowsInserted == 1;
	}

}
