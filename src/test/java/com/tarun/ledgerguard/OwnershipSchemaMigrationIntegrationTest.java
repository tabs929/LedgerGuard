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
 * Verifies the V7 Flyway migration (Task 17: customer ownership and
 * principal-scoped idempotency) purely at the database level, against a
 * real, isolated PostgreSQL 16.4 Testcontainer that runs every migration
 * from scratch -- V1-V6 are exercised unmodified by this same run.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class OwnershipSchemaMigrationIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	DataSource dataSource;

	@Test
	void flywayAppliesV7Successfully() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT description, success FROM flyway_schema_history WHERE version = '7'")) {
			assertThat(resultSet.next()).isTrue();
			assertThat(resultSet.getBoolean("success")).isTrue();
		}
	}

	@Test
	void customerSubjectColumnExistsOnAccount() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT column_name FROM information_schema.columns "
								+ "WHERE table_name = 'account' AND column_name = 'customer_subject'");
				ResultSet resultSet = statement.executeQuery()) {
			assertThat(resultSet.next()).isTrue();
		}
	}

	@Test
	void principalSubjectColumnExistsAndIsNotNullOnIdempotencyKey() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT is_nullable FROM information_schema.columns "
								+ "WHERE table_name = 'idempotency_key' AND column_name = 'principal_subject'");
				ResultSet resultSet = statement.executeQuery()) {
			assertThat(resultSet.next()).isTrue();
			assertThat(resultSet.getString("is_nullable")).isEqualTo("NO");
		}
	}

	@Test
	void ownershipCheckConstraintExists() throws SQLException {
		assertThat(constraintNames("account")).contains("chk_account_ownership");
	}

	@Test
	void compositeIdempotencyUniqueConstraintExists() throws SQLException {
		Set<String> constraints = constraintNames("idempotency_key");
		assertThat(constraints).contains("uq_idempotency_key_principal");
		assertThat(constraints).doesNotContain("uq_idempotency_key");
	}

	@Test
	void customerAccountWithoutASubjectIsRejected() {
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"INSERT INTO account (account_category, account_class, account_purpose, owner_name, "
									+ "currency) VALUES ('CUSTOMER', 'LIABILITY', 'CUSTOMER_WALLET', 'No Subject', 'USD')")) {
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("chk_account_ownership");
	}

	@Test
	void systemAccountWithASubjectIsRejected() {
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"INSERT INTO account (account_category, account_class, account_purpose, owner_name, "
									+ "currency, customer_subject) VALUES "
									+ "('SYSTEM', 'ASSET', 'EXTERNAL_FUNDING', 'Bad System Owner', 'GBP', 'someone')")) {
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class).hasMessageContaining("chk_account_ownership");
	}

	@Test
	void customerAccountWithASubjectIsAccepted() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO account (account_category, account_class, account_purpose, owner_name, "
								+ "currency, customer_subject) VALUES "
								+ "('CUSTOMER', 'LIABILITY', 'CUSTOMER_WALLET', 'Valid Owner', 'NZD', 'valid-subject')")) {
			statement.executeUpdate();
		}
	}

	@Test
	void twoDifferentPrincipalsCanReuseTheSameIdempotencyKeyStringAtTheDatabaseLevel() throws SQLException {
		UUID accountA = insertCustomerWallet("Idem Owner A", "principal-a");
		UUID accountB = insertCustomerWallet("Idem Owner B", "principal-b");
		String sharedKey = "shared-key-" + UUID.randomUUID();

		insertIdempotencyRow(sharedKey, "principal-a", accountA);
		// Must NOT fail -- uniqueness is (principal_subject, idempotency_key),
		// not idempotency_key alone.
		insertIdempotencyRow(sharedKey, "principal-b", accountB);
	}

	@Test
	void theSamePrincipalCannotReuseTheSameKeyStringTwice() throws SQLException {
		UUID accountA = insertCustomerWallet("Idem Owner Dup", "principal-dup");
		String sharedKey = "dup-key-" + UUID.randomUUID();
		insertIdempotencyRow(sharedKey, "principal-dup", accountA);

		assertThatThrownBy(() -> insertIdempotencyRow(sharedKey, "principal-dup", accountA))
				.isInstanceOf(SQLException.class).hasMessageContaining("uq_idempotency_key_principal");
	}

	private UUID insertCustomerWallet(String ownerName, String customerSubject) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO account (account_category, account_class, account_purpose, owner_name, "
								+ "currency, customer_subject) VALUES "
								+ "('CUSTOMER', 'LIABILITY', 'CUSTOMER_WALLET', ?, 'USD', ?) RETURNING id")) {
			statement.setString(1, ownerName);
			statement.setString(2, customerSubject);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return (UUID) resultSet.getObject("id");
			}
		}
	}

	private void insertIdempotencyRow(String idempotencyKey, String principalSubject, UUID primaryAccountId)
			throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement transactionStatement = connection.prepareStatement(
						"INSERT INTO ledger_transaction (transaction_type, status) VALUES ('DEPOSIT', 'COMPLETED') "
								+ "RETURNING id")) {
			UUID transactionId;
			try (ResultSet resultSet = transactionStatement.executeQuery()) {
				resultSet.next();
				transactionId = (UUID) resultSet.getObject("id");
			}
			try (PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO idempotency_key (idempotency_key, principal_subject, operation_type, "
							+ "primary_account_id, amount, currency, command_hash, ledger_transaction_id, "
							+ "response_status, response_body) VALUES "
							+ "(?, ?, 'DEPOSIT', ?, 10.0000, 'USD', ?, ?, 201, '{}')")) {
				statement.setString(1, idempotencyKey);
				statement.setString(2, principalSubject);
				statement.setObject(3, primaryAccountId);
				statement.setString(4, "0".repeat(64));
				statement.setObject(5, transactionId);
				statement.executeUpdate();
			}
		}
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

}
