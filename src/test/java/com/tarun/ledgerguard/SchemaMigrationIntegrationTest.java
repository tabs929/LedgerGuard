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
 * Verifies the V1 Flyway migration (Task 2) purely at the database level:
 * tables, constraints, indexes, triggers and seed data. No JPA entities,
 * repositories or services exist yet, so this test talks to the database
 * directly through the application's configured DataSource.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest
class SchemaMigrationIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	DataSource dataSource;

	@Test
	void flywayAppliesV1Successfully() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT version, description, success FROM flyway_schema_history WHERE version = '1'")) {
			assertThat(resultSet.next()).isTrue();
			assertThat(resultSet.getString("description")).isEqualTo("init account ledger schema");
			assertThat(resultSet.getBoolean("success")).isTrue();
		}
	}

	@Test
	void allExpectedTablesExist() throws SQLException {
		Set<String> tables = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")) {
			while (resultSet.next()) {
				tables.add(resultSet.getString("table_name"));
			}
		}
		assertThat(tables).contains("account", "ledger_transaction", "ledger_entry");
	}

	@Test
	void allExpectedConstraintsExist() throws SQLException {
		Set<String> accountConstraints = constraintNames("account");
		assertThat(accountConstraints).contains(
				"chk_account_category",
				"chk_account_class",
				"chk_account_purpose",
				"chk_account_currency_format",
				"chk_account_balance_nonneg",
				"chk_account_taxonomy_combination");

		Set<String> transactionConstraints = constraintNames("ledger_transaction");
		assertThat(transactionConstraints).contains("chk_transaction_type", "chk_transaction_status");

		Set<String> entryConstraints = constraintNames("ledger_entry");
		assertThat(entryConstraints).contains(
				"chk_ledger_entry_type",
				"chk_ledger_entry_amount_positive",
				"chk_ledger_entry_currency_format");
	}

	@Test
	void allExpectedIndexesExist() throws SQLException {
		Set<String> accountIndexes = indexNames("account");
		assertThat(accountIndexes).contains("uq_system_account_purpose_currency");

		Set<String> entryIndexes = indexNames("ledger_entry");
		assertThat(entryIndexes).contains("idx_ledger_entry_account_id", "idx_ledger_entry_transaction_id");
	}

	@Test
	void allExpectedTriggersExist() throws SQLException {
		assertThat(triggerNames("ledger_transaction")).contains("trg_ledger_transaction_immutable");
		assertThat(triggerNames("ledger_entry")).contains("trg_ledger_entry_immutable");
	}

	@Test
	void validAccountTaxonomyCombinationsAreAccepted() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			insertAccount(connection, "CUSTOMER", "LIABILITY", "CUSTOMER_WALLET", "Alice", "EUR");
			insertAccount(connection, "SYSTEM", "ASSET", "EXTERNAL_FUNDING", "EXTERNAL_FUNDING", "EUR");
		}
	}

	@Test
	void invalidAccountTaxonomyCombinationIsRejected() {
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection()) {
				insertAccount(connection, "CUSTOMER", "ASSET", "EXTERNAL_FUNDING", "Bad Combo", "GBP");
			}
		}).isInstanceOf(SQLException.class)
				.hasMessageContaining("chk_account_taxonomy_combination");
	}

	@Test
	void invalidCurrencyIsRejected() {
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection()) {
				insertAccount(connection, "CUSTOMER", "LIABILITY", "CUSTOMER_WALLET", "Bad Currency", "us");
			}
		}).isInstanceOf(SQLException.class)
				.hasMessageContaining("chk_account_currency_format");
	}

	@Test
	void negativeAccountBalanceIsRejected() {
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"INSERT INTO account (account_category, account_class, account_purpose, owner_name, currency, balance) "
									+ "VALUES ('CUSTOMER', 'LIABILITY', 'CUSTOMER_WALLET', 'Negative Balance', 'CHF', -1)")) {
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class)
				.hasMessageContaining("chk_account_balance_nonneg");
	}

	@Test
	void nonPositiveLedgerEntryAmountIsRejected() throws SQLException {
		UUID transactionId;
		UUID accountId;
		try (Connection connection = dataSource.getConnection()) {
			accountId = insertAccount(connection, "CUSTOMER", "LIABILITY", "CUSTOMER_WALLET", "Zero Amount", "AUD");
			transactionId = insertLedgerTransaction(connection, "DEPOSIT");
		}

		UUID finalTransactionId = transactionId;
		UUID finalAccountId = accountId;
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection()) {
				insertLedgerEntry(connection, finalTransactionId, finalAccountId, "DEBIT", "0", "AUD");
			}
		}).isInstanceOf(SQLException.class)
				.hasMessageContaining("chk_ledger_entry_amount_positive");
	}

	@Test
	void secondSystemFundingAccountForSameCurrencyIsRejected() {
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection()) {
				insertAccount(connection, "SYSTEM", "ASSET", "EXTERNAL_FUNDING", "EXTERNAL_FUNDING", "USD");
			}
		}).isInstanceOf(SQLException.class)
				.hasMessageContaining("uq_system_account_purpose_currency");
	}

	@Test
	void validLedgerTransactionAndEntriesCanBeInserted() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			UUID senderId = insertAccount(connection, "CUSTOMER", "LIABILITY", "CUSTOMER_WALLET", "Sender", "JPY");
			UUID recipientId = insertAccount(connection, "CUSTOMER", "LIABILITY", "CUSTOMER_WALLET", "Recipient", "JPY");
			UUID transactionId = insertLedgerTransaction(connection, "TRANSFER");

			insertLedgerEntry(connection, transactionId, senderId, "DEBIT", "25.0000", "JPY");
			insertLedgerEntry(connection, transactionId, recipientId, "CREDIT", "25.0000", "JPY");

			try (PreparedStatement count = connection.prepareStatement(
					"SELECT COUNT(*) FROM ledger_entry WHERE transaction_id = ?")) {
				count.setObject(1, transactionId);
				try (ResultSet resultSet = count.executeQuery()) {
					assertThat(resultSet.next()).isTrue();
					assertThat(resultSet.getInt(1)).isEqualTo(2);
				}
			}
		}
	}

	@Test
	void updateIsRejectedForLedgerTransaction() throws SQLException {
		UUID transactionId;
		try (Connection connection = dataSource.getConnection()) {
			transactionId = insertLedgerTransaction(connection, "DEPOSIT");
		}
		UUID finalId = transactionId;
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"UPDATE ledger_transaction SET status = 'COMPLETED' WHERE id = ?")) {
				statement.setObject(1, finalId);
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class)
				.hasMessageContaining("ledger_transaction rows are immutable");
	}

	@Test
	void deleteIsRejectedForLedgerTransaction() throws SQLException {
		UUID transactionId;
		try (Connection connection = dataSource.getConnection()) {
			transactionId = insertLedgerTransaction(connection, "DEPOSIT");
		}
		UUID finalId = transactionId;
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"DELETE FROM ledger_transaction WHERE id = ?")) {
				statement.setObject(1, finalId);
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class)
				.hasMessageContaining("ledger_transaction rows are immutable");
	}

	@Test
	void updateIsRejectedForLedgerEntry() throws SQLException {
		UUID entryId;
		try (Connection connection = dataSource.getConnection()) {
			UUID accountId = insertAccount(connection, "CUSTOMER", "LIABILITY", "CUSTOMER_WALLET", "Immutable Update", "CAD");
			UUID transactionId = insertLedgerTransaction(connection, "DEPOSIT");
			entryId = insertLedgerEntry(connection, transactionId, accountId, "CREDIT", "10.0000", "CAD");
		}
		UUID finalId = entryId;
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"UPDATE ledger_entry SET amount = 20.0000 WHERE id = ?")) {
				statement.setObject(1, finalId);
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class)
				.hasMessageContaining("ledger_entry rows are immutable");
	}

	@Test
	void deleteIsRejectedForLedgerEntry() throws SQLException {
		UUID entryId;
		try (Connection connection = dataSource.getConnection()) {
			UUID accountId = insertAccount(connection, "CUSTOMER", "LIABILITY", "CUSTOMER_WALLET", "Immutable Delete", "NZD");
			UUID transactionId = insertLedgerTransaction(connection, "DEPOSIT");
			entryId = insertLedgerEntry(connection, transactionId, accountId, "CREDIT", "10.0000", "NZD");
		}
		UUID finalId = entryId;
		assertThatThrownBy(() -> {
			try (Connection connection = dataSource.getConnection();
					PreparedStatement statement = connection.prepareStatement(
							"DELETE FROM ledger_entry WHERE id = ?")) {
				statement.setObject(1, finalId);
				statement.executeUpdate();
			}
		}).isInstanceOf(SQLException.class)
				.hasMessageContaining("ledger_entry rows are immutable");
	}

	private UUID insertAccount(Connection connection, String category, String accountClass, String purpose,
			String ownerName, String currency) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO account (account_category, account_class, account_purpose, owner_name, currency) "
						+ "VALUES (?, ?, ?, ?, ?) RETURNING id")) {
			statement.setString(1, category);
			statement.setString(2, accountClass);
			statement.setString(3, purpose);
			statement.setString(4, ownerName);
			statement.setString(5, currency);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return (UUID) resultSet.getObject("id");
			}
		}
	}

	private UUID insertLedgerTransaction(Connection connection, String transactionType) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO ledger_transaction (transaction_type, status) VALUES (?, 'COMPLETED') RETURNING id")) {
			statement.setString(1, transactionType);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return (UUID) resultSet.getObject("id");
			}
		}
	}

	private UUID insertLedgerEntry(Connection connection, UUID transactionId, UUID accountId, String entryType,
			String amount, String currency) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO ledger_entry (transaction_id, account_id, entry_type, amount, currency) "
						+ "VALUES (?, ?, ?, ?, ?) RETURNING id")) {
			statement.setObject(1, transactionId);
			statement.setObject(2, accountId);
			statement.setString(3, entryType);
			statement.setBigDecimal(4, new java.math.BigDecimal(amount));
			statement.setString(5, currency);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return (UUID) resultSet.getObject("id");
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

	private Set<String> indexNames(String tableName) throws SQLException {
		Set<String> names = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT indexname FROM pg_indexes WHERE tablename = ?")) {
			statement.setString(1, tableName);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					names.add(resultSet.getString("indexname"));
				}
			}
		}
		return names;
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
