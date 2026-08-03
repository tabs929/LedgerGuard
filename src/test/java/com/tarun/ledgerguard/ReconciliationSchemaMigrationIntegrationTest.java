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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the V6 Flyway migration (Task 15) at the database level, in
 * the same style as {@link SettlementSchemaMigrationIntegrationTest} for
 * V5: tables, constraints, indexes, foreign keys, and append-only
 * triggers — all against a real, isolated PostgreSQL 16.4 Testcontainer
 * that runs every migration (V1-V6) from scratch.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class ReconciliationSchemaMigrationIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	DataSource dataSource;

	@Test
	void allMigrationsFromV1ToV6ApplySuccessfully() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT version, success FROM flyway_schema_history WHERE version IN ('1','2','3','4','5','6') "
								+ "ORDER BY version")) {
			int count = 0;
			while (resultSet.next()) {
				assertThat(resultSet.getBoolean("success")).isTrue();
				count++;
			}
			assertThat(count).isEqualTo(6);
		}
	}

	@Test
	void v1ThroughV5RemainUnchangedByV6() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT version, description FROM flyway_schema_history WHERE version IN ('1','2','3','4','5') "
								+ "ORDER BY version::int")) {
			Set<String> descriptions = new HashSet<>();
			while (resultSet.next()) {
				descriptions.add(resultSet.getString("description"));
			}
			assertThat(descriptions).contains(
					"init account ledger schema",
					"add idempotency key",
					"add transactional outbox",
					"add processed event deduplication",
					"add settlement import");
		}
	}

	@Test
	void bothReconciliationTablesExist() throws SQLException {
		Set<String> tables = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
			while (resultSet.next()) {
				tables.add(resultSet.getString("table_name"));
			}
		}
		assertThat(tables).contains("reconciliation_run", "reconciliation_result");
	}

	@Test
	void reconciliationRunHasExpectedColumns() throws SQLException {
		assertThat(columnNames("reconciliation_run")).containsExactlyInAnyOrder(
				"id", "settlement_import_id", "algorithm_version", "total_result_count", "matched_count",
				"discrepancy_count", "inconsistent_count", "created_at");
	}

	@Test
	void reconciliationResultHasExpectedColumns() throws SQLException {
		assertThat(columnNames("reconciliation_result")).containsExactlyInAnyOrder(
				"id", "run_id", "settlement_record_id", "reported_transaction_id", "outcome", "reported_amount",
				"reported_currency", "internal_amount", "internal_currency", "created_at");
	}

	@Test
	void primaryKeysExistOnBothTables() throws SQLException {
		assertThat(constraintTypes("reconciliation_run")).contains("p");
		assertThat(constraintTypes("reconciliation_result")).contains("p");
	}

	@Test
	void foreignKeysExist() throws SQLException {
		assertThat(foreignKeyTargets("reconciliation_run")).contains("settlement_import");
		assertThat(foreignKeyTargets("reconciliation_result")).contains("reconciliation_run", "settlement_record");
	}

	@Test
	void uniqueSettlementImportAlgorithmVersionConstraintExists() throws SQLException {
		assertThat(constraintNames("reconciliation_run"))
				.contains("uq_reconciliation_run_settlement_import_algorithm_version");
	}

	@Test
	void uniquenessPermitsADifferentAlgorithmVersionForTheSameImport() throws SQLException {
		// Proves the constraint is on (settlement_import_id,
		// algorithm_version), not settlement_import_id alone -- a second
		// row for the same import but a different algorithm version must
		// be accepted at the database level.
		try (Connection connection = dataSource.getConnection()) {
			UUID importId = insertMinimalImport(connection);
			insertRunWithVersion(connection, importId, 1);
			// Must not throw.
			insertRunWithVersion(connection, importId, 2);
		}
	}

	@Test
	void uniqueRunSettlementRecordConstraintExists() throws SQLException {
		assertThat(constraintNames("reconciliation_result")).contains("uq_reconciliation_result_run_settlement_record");
	}

	@Test
	void validationConstraintsExist() throws SQLException {
		assertThat(constraintNames("reconciliation_run")).contains(
				"chk_reconciliation_run_algorithm_version_positive",
				"chk_reconciliation_run_total_result_count_nonneg",
				"chk_reconciliation_run_matched_count_nonneg",
				"chk_reconciliation_run_discrepancy_count_nonneg",
				"chk_reconciliation_run_inconsistent_count_nonneg",
				"chk_reconciliation_run_counts_consistent");

		assertThat(constraintNames("reconciliation_result")).contains(
				"chk_reconciliation_result_outcome",
				"chk_reconciliation_result_reported_amount_positive",
				"chk_reconciliation_result_reported_currency_format",
				"chk_reconciliation_result_internal_amount_positive",
				"chk_reconciliation_result_internal_currency_format",
				"chk_reconciliation_result_internal_pair_together");
	}

	@Test
	void appendOnlyUpdateTriggersExist() throws SQLException {
		assertThat(triggerNames("reconciliation_run")).contains("trg_reconciliation_run_no_update");
		assertThat(triggerNames("reconciliation_result")).contains("trg_reconciliation_result_no_update");
	}

	@Test
	void appendOnlyDeleteTriggersExist() throws SQLException {
		assertThat(triggerNames("reconciliation_run")).contains("trg_reconciliation_run_no_delete");
		assertThat(triggerNames("reconciliation_result")).contains("trg_reconciliation_result_no_delete");
	}

	@Test
	void updateIsRejectedForReconciliationRun() throws SQLException {
		UUID id = insertMinimalRun();
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"UPDATE reconciliation_run SET matched_count = 99 WHERE id = ?")) {
				statement.setObject(1, id);
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("immutable");
	}

	@Test
	void deleteIsRejectedForReconciliationRun() throws SQLException {
		UUID id = insertMinimalRun();
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"DELETE FROM reconciliation_run WHERE id = ?")) {
				statement.setObject(1, id);
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("immutable");
	}

	@Test
	void updateIsRejectedForReconciliationResult() throws SQLException {
		UUID runId = insertMinimalRun();
		UUID resultId = insertMinimalResult(runId);
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"UPDATE reconciliation_result SET outcome = 'MATCHED' WHERE id = ?")) {
				statement.setObject(1, resultId);
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("immutable");
	}

	@Test
	void deleteIsRejectedForReconciliationResult() throws SQLException {
		UUID runId = insertMinimalRun();
		UUID resultId = insertMinimalResult(runId);
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"DELETE FROM reconciliation_result WHERE id = ?")) {
				statement.setObject(1, resultId);
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("immutable");
	}

	@Test
	void inconsistentRunCountsAreRejected() {
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection()) {
				UUID importId = insertMinimalImport(connection);
				try (PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO reconciliation_run (settlement_import_id, algorithm_version, "
								+ "total_result_count, matched_count, discrepancy_count, inconsistent_count) "
								+ "VALUES (?, 1, 10, 5, 1, 1)")) {
					statement.setObject(1, importId);
					statement.executeUpdate();
				}
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("chk_reconciliation_run_counts_consistent");
	}

	@Test
	void invalidOutcomeValueIsRejected() {
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection()) {
				UUID runId = insertMinimalRun(connection);
				UUID importId = insertMinimalImport(connection);
				UUID settlementRecordId = insertMinimalSettlementRecord(connection, importId);
				try (PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO reconciliation_result (run_id, settlement_record_id, reported_transaction_id, "
								+ "outcome, reported_amount, reported_currency) "
								+ "VALUES (?, ?, gen_random_uuid(), 'NOT_A_REAL_OUTCOME', 1.00, 'USD')")) {
					statement.setObject(1, runId);
					statement.setObject(2, settlementRecordId);
					statement.executeUpdate();
				}
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("chk_reconciliation_result_outcome");
	}

	@Test
	void mismatchedInternalAmountCurrencyPairIsRejected() {
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection()) {
				UUID runId = insertMinimalRun(connection);
				UUID importId = insertMinimalImport(connection);
				UUID settlementRecordId = insertMinimalSettlementRecord(connection, importId);
				try (PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO reconciliation_result (run_id, settlement_record_id, reported_transaction_id, "
								+ "outcome, reported_amount, reported_currency, internal_amount, internal_currency) "
								+ "VALUES (?, ?, gen_random_uuid(), 'MATCHED', 1.00, 'USD', 1.0000, NULL)")) {
					statement.setObject(1, runId);
					statement.setObject(2, settlementRecordId);
					statement.executeUpdate();
				}
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("chk_reconciliation_result_internal_pair_together");
	}

	private void insertRunWithVersion(Connection connection, UUID importId, int algorithmVersion) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO reconciliation_run (settlement_import_id, algorithm_version, total_result_count, "
						+ "matched_count, discrepancy_count, inconsistent_count) VALUES (?, ?, 0, 0, 0, 0)")) {
			statement.setObject(1, importId);
			statement.setInt(2, algorithmVersion);
			statement.executeUpdate();
		}
	}

	private UUID insertMinimalRun() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			return insertMinimalRun(connection);
		}
	}

	private UUID insertMinimalRun(Connection connection) throws SQLException {
		UUID importId = insertMinimalImport(connection);
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO reconciliation_run (settlement_import_id, algorithm_version, total_result_count, "
						+ "matched_count, discrepancy_count, inconsistent_count) VALUES (?, 1, 0, 0, 0, 0) RETURNING id")) {
			statement.setObject(1, importId);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return (UUID) resultSet.getObject("id");
			}
		}
	}

	private UUID insertMinimalResult(UUID runId) throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			UUID importId = insertMinimalImport(connection);
			UUID settlementRecordId = insertMinimalSettlementRecord(connection, importId);
			try (PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO reconciliation_result (run_id, settlement_record_id, reported_transaction_id, "
							+ "outcome, reported_amount, reported_currency) "
							+ "VALUES (?, ?, gen_random_uuid(), 'INTERNAL_TRANSACTION_NOT_FOUND', 1.00, 'USD') "
							+ "RETURNING id")) {
				statement.setObject(1, runId);
				statement.setObject(2, settlementRecordId);
				try (ResultSet resultSet = statement.executeQuery()) {
					resultSet.next();
					return (UUID) resultSet.getObject("id");
				}
			}
		}
	}

	private UUID insertMinimalSettlementRecord(Connection connection, UUID importId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO settlement_record (normalized_source, external_reference, transaction_id, amount, "
						+ "currency, settled_at, row_hash, first_import_id, source_row_number) "
						+ "VALUES (?, ?, gen_random_uuid(), 1.00, 'USD', now(), ?, ?, 1) RETURNING id")) {
			statement.setString(1, "schema-test-record-" + UUID.randomUUID());
			statement.setString(2, "ref-" + UUID.randomUUID());
			statement.setString(3, "c".repeat(64));
			statement.setObject(4, importId);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return (UUID) resultSet.getObject("id");
			}
		}
	}

	private UUID insertMinimalImport(Connection connection) throws SQLException {
		String source = "schema-test-" + UUID.randomUUID();
		String hash = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO settlement_import (source, normalized_source, file_hash, file_size_bytes, "
						+ "total_row_count, inserted_row_count, duplicate_row_count) "
						+ "VALUES (?, ?, ?, 10, 1, 1, 0) RETURNING id")) {
			statement.setString(1, source);
			statement.setString(2, source);
			statement.setString(3, hash.substring(0, 64));
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return (UUID) resultSet.getObject("id");
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

	private Set<String> foreignKeyTargets(String tableName) throws SQLException {
		Set<String> targets = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT confrelid::regclass::text AS referenced_table FROM pg_constraint "
								+ "WHERE conrelid = ?::regclass AND contype = 'f'")) {
			statement.setString(1, tableName);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					targets.add(resultSet.getString("referenced_table"));
				}
			}
		}
		return targets;
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
