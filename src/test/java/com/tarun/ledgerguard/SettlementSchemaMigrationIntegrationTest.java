package com.tarun.ledgerguard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the V5 Flyway migration (Task 14) at the database level, in
 * the same style as {@link SchemaMigrationIntegrationTest} for V1: tables,
 * constraints, indexes, foreign keys, and append-only triggers -- all
 * against a real, isolated PostgreSQL 16.4 Testcontainer that runs every
 * migration (V1-V5) from scratch.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class SettlementSchemaMigrationIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	DataSource dataSource;

	@Test
	void allMigrationsFromV1ToV5ApplySuccessfully() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT version, success FROM flyway_schema_history WHERE version IN ('1','2','3','4','5') "
								+ "ORDER BY version")) {
			int count = 0;
			while (resultSet.next()) {
				assertThat(resultSet.getBoolean("success")).isTrue();
				count++;
			}
			assertThat(count).isEqualTo(5);
		}
	}

	@Test
	void v1ThroughV4RemainUnchangedByV5() throws SQLException {
		// V5 is the only new migration -- V1-V4 must still be present with
		// their original, unmodified checksums recorded by Flyway (a
		// changed migration file would show as a validation failure at
		// startup, which this application context would never have
		// reached if that had happened).
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT version, description FROM flyway_schema_history WHERE version IN ('1','2','3','4') "
								+ "ORDER BY version::int")) {
			Set<String> descriptions = new HashSet<>();
			while (resultSet.next()) {
				descriptions.add(resultSet.getString("description"));
			}
			assertThat(descriptions).contains(
					"init account ledger schema",
					"add idempotency key",
					"add transactional outbox",
					"add processed event deduplication");
		}
	}

	@Test
	void bothSettlementTablesExist() throws SQLException {
		Set<String> tables = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
			while (resultSet.next()) {
				tables.add(resultSet.getString("table_name"));
			}
		}
		assertThat(tables).contains("settlement_import", "settlement_record");
	}

	@Test
	void settlementImportHasExpectedColumns() throws SQLException {
		assertThat(columnNames("settlement_import")).containsExactlyInAnyOrder(
				"id", "source", "normalized_source", "original_filename", "file_hash", "file_size_bytes",
				"total_row_count", "inserted_row_count", "duplicate_row_count", "imported_at");
	}

	@Test
	void settlementRecordHasExpectedColumns() throws SQLException {
		assertThat(columnNames("settlement_record")).containsExactlyInAnyOrder(
				"id", "normalized_source", "external_reference", "transaction_id", "amount", "currency",
				"settled_at", "row_hash", "first_import_id", "source_row_number", "created_at");
	}

	@Test
	void primaryKeysExistOnBothTables() throws SQLException {
		assertThat(constraintTypes("settlement_import")).contains("p");
		assertThat(constraintTypes("settlement_record")).contains("p");
	}

	@Test
	void uniqueFileIdentityConstraintExists() throws SQLException {
		assertThat(constraintNames("settlement_import")).contains("uq_settlement_import_source_file");
	}

	@Test
	void uniqueSettlementRowIdentityConstraintExists() throws SQLException {
		assertThat(constraintNames("settlement_record")).contains("uq_settlement_record_source_reference");
	}

	@Test
	void noForeignKeyExistsFromSettlementRecordToLedgerTransaction() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT confrelid::regclass::text AS referenced_table FROM pg_constraint "
								+ "WHERE conrelid = 'settlement_record'::regclass AND contype = 'f'")) {
			try (ResultSet resultSet = statement.executeQuery()) {
				Set<String> referencedTables = new HashSet<>();
				while (resultSet.next()) {
					referencedTables.add(resultSet.getString("referenced_table"));
				}
				assertThat(referencedTables).doesNotContain("ledger_transaction");
			}
		}
	}

	@Test
	void foreignKeyToFirstImportExists() throws SQLException {
		assertThat(constraintNames("settlement_record")).contains("fk_settlement_record_first_import");
	}

	@Test
	void validationConstraintsExist() throws SQLException {
		assertThat(constraintNames("settlement_import")).contains(
				"chk_settlement_import_source_nonblank",
				"chk_settlement_import_file_hash_format",
				"chk_settlement_import_file_size_bounds",
				"chk_settlement_import_total_row_count_positive",
				"chk_settlement_import_inserted_row_count_nonneg",
				"chk_settlement_import_duplicate_row_count_nonneg",
				"chk_settlement_import_row_counts_consistent");

		assertThat(constraintNames("settlement_record")).contains(
				"chk_settlement_record_external_reference_nonblank",
				"chk_settlement_record_amount_positive",
				"chk_settlement_record_currency_format",
				"chk_settlement_record_row_hash_format",
				"chk_settlement_record_source_row_number_positive");
	}

	@Test
	void appendOnlyUpdateTriggersExist() throws SQLException {
		assertThat(triggerNames("settlement_import")).contains("trg_settlement_import_no_update");
		assertThat(triggerNames("settlement_record")).contains("trg_settlement_record_no_update");
	}

	@Test
	void appendOnlyDeleteTriggersExist() throws SQLException {
		assertThat(triggerNames("settlement_import")).contains("trg_settlement_import_no_delete");
		assertThat(triggerNames("settlement_record")).contains("trg_settlement_record_no_delete");
	}

	@Test
	void updateIsRejectedForSettlementImport() throws SQLException {
		java.util.UUID id = insertMinimalImport();
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"UPDATE settlement_import SET inserted_row_count = 99 WHERE id = ?")) {
				statement.setObject(1, id);
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("immutable");
	}

	@Test
	void deleteIsRejectedForSettlementImport() throws SQLException {
		java.util.UUID id = insertMinimalImport();
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"DELETE FROM settlement_import WHERE id = ?")) {
				statement.setObject(1, id);
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("immutable");
	}

	@Test
	void updateIsRejectedForSettlementRecord() throws SQLException {
		java.util.UUID importId = insertMinimalImport();
		java.util.UUID recordId = insertMinimalRecord(importId, "ref-update");
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"UPDATE settlement_record SET amount = 999.00 WHERE id = ?")) {
				statement.setObject(1, recordId);
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("immutable");
	}

	@Test
	void deleteIsRejectedForSettlementRecord() throws SQLException {
		java.util.UUID importId = insertMinimalImport();
		java.util.UUID recordId = insertMinimalRecord(importId, "ref-delete");
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"DELETE FROM settlement_record WHERE id = ?")) {
				statement.setObject(1, recordId);
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("immutable");
	}

	@Test
	void inconsistentRowCountsAreRejected() {
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"INSERT INTO settlement_import (source, normalized_source, file_hash, file_size_bytes, "
									+ "total_row_count, inserted_row_count, duplicate_row_count) "
									+ "VALUES ('src', 'src', repeat('a', 64), 10, 5, 1, 1)")) {
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("chk_settlement_import_row_counts_consistent");
	}

	@Test
	void invalidRowHashFormatIsRejected() {
		java.util.UUID importId = null;
		try {
			importId = insertMinimalImport();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		java.util.UUID finalImportId = importId;
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"INSERT INTO settlement_record (normalized_source, external_reference, transaction_id, "
									+ "amount, currency, settled_at, row_hash, first_import_id, source_row_number) "
									+ "VALUES ('src', 'bad-hash-ref', gen_random_uuid(), 1.00, 'USD', now(), 'not-a-hash', ?, 1)")) {
				statement.setObject(1, finalImportId);
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("chk_settlement_record_row_hash_format");
	}

	@Test
	void invalidSourceRowNumberIsRejected() {
		java.util.UUID importId;
		try {
			importId = insertMinimalImport();
		} catch (SQLException e) {
			throw new RuntimeException(e);
		}
		java.util.UUID finalImportId = importId;
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"INSERT INTO settlement_record (normalized_source, external_reference, transaction_id, "
									+ "amount, currency, settled_at, row_hash, first_import_id, source_row_number) "
									+ "VALUES ('src', 'bad-row-number', gen_random_uuid(), 1.00, 'USD', now(), repeat('a', 64), ?, 0)")) {
				statement.setObject(1, finalImportId);
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("chk_settlement_record_source_row_number_positive");
	}

	private java.util.UUID insertMinimalImport() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO settlement_import (source, normalized_source, file_hash, file_size_bytes, "
								+ "total_row_count, inserted_row_count, duplicate_row_count) "
								+ "VALUES (?, ?, ?, 10, 1, 1, 0) RETURNING id")) {
			String uniqueHash = java.util.UUID.randomUUID().toString().replace("-", "") + "0".repeat(32);
			String source = "schema-test-" + java.util.UUID.randomUUID();
			statement.setString(1, source);
			statement.setString(2, source);
			statement.setString(3, uniqueHash.substring(0, 64));
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return (java.util.UUID) resultSet.getObject("id");
			}
		}
	}

	private java.util.UUID insertMinimalRecord(java.util.UUID importId, String externalReference) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO settlement_record (normalized_source, external_reference, transaction_id, "
								+ "amount, currency, settled_at, row_hash, first_import_id, source_row_number) "
								+ "VALUES (?, ?, gen_random_uuid(), 1.00, 'USD', now(), ?, ?, 1) RETURNING id")) {
			statement.setString(1, "schema-test-record-" + java.util.UUID.randomUUID());
			statement.setString(2, externalReference);
			statement.setString(3, "b".repeat(64));
			statement.setObject(4, importId);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return (java.util.UUID) resultSet.getObject("id");
			}
		}
	}

	private Set<String> columnNames(String tableName) throws SQLException {
		Set<String> names = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT column_name FROM information_schema.columns WHERE table_name = ?")) {
			statement.setString(1, tableName);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					names.add(resultSet.getString("column_name"));
				}
			}
		}
		return names;
	}

	private Set<String> constraintNames(String tableName) throws SQLException {
		Set<String> names = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT conname FROM pg_constraint WHERE conrelid = ?::regclass")) {
			statement.setString(1, tableName);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					names.add(resultSet.getString("conname"));
				}
			}
		}
		return names;
	}

	private Set<String> constraintTypes(String tableName) throws SQLException {
		Set<String> types = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT contype FROM pg_constraint WHERE conrelid = ?::regclass")) {
			statement.setString(1, tableName);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					types.add(resultSet.getString("contype"));
				}
			}
		}
		return types;
	}

	private Set<String> triggerNames(String tableName) throws SQLException {
		Set<String> names = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT tgname FROM pg_trigger WHERE tgrelid = ?::regclass AND NOT tgisinternal")) {
			statement.setString(1, tableName);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					names.add(resultSet.getString("tgname"));
				}
			}
		}
		return names;
	}

}
