package com.tarun.ledgerguard.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarun.ledgerguard.account.dto.AccountResponse;
import com.tarun.ledgerguard.account.dto.DepositResponse;
import com.tarun.ledgerguard.security.TestAuthSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises deposits (Task 4) at the HTTP boundary and verifies persisted
 * database state directly via JDBC, against a real, isolated PostgreSQL
 * 16.4 Testcontainer that runs the Flyway migrations from scratch. No
 * mocks are used for persistence behavior; concurrency is exercised
 * against real PostgreSQL row locking, not Java-only synchronization.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DepositIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	TestRestTemplate restTemplate;

	@Autowired
	DataSource dataSource;

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	void validDepositReturnsDocumentedSuccessAndResponse() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Deposit Owner 1");

		ResponseEntity<DepositResponse> response = postDeposit(accountId, Map.of(
				"amount", "100.00",
				"currency", "USD"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		DepositResponse body = response.getBody();
		assertThat(body).isNotNull();
		assertThat(body.transactionId()).isNotNull();
		assertThat(body.accountId()).isEqualTo(accountId);
		assertThat(body.amount()).isEqualByComparingTo("100.00");
		assertThat(body.currency()).isEqualTo("USD");
		assertThat(body.newBalance()).isEqualByComparingTo("100.00");
		assertThat(body.createdAt()).isNotNull();
	}

	@Test
	void validDepositCreatesOneBalancedTransactionWithTwoEntries() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Deposit Owner 2");
		UUID fundingAccountId = fetchUsdFundingAccountId();

		ResponseEntity<DepositResponse> response = postDeposit(accountId, Map.of(
				"amount", "42.50",
				"currency", "USD"));
		UUID transactionId = response.getBody().transactionId();

		assertThat(fetchTransactionTypeAndStatus(transactionId)).isEqualTo(Map.entry("DEPOSIT", "COMPLETED"));

		List<Map<String, Object>> entries = fetchEntriesForTransaction(transactionId);
		assertThat(entries).hasSize(2);

		Map<String, Object> debit = entries.stream()
				.filter(e -> e.get("entry_type").equals("DEBIT"))
				.findFirst().orElseThrow();
		Map<String, Object> credit = entries.stream()
				.filter(e -> e.get("entry_type").equals("CREDIT"))
				.findFirst().orElseThrow();

		assertThat(debit.get("account_id")).isEqualTo(fundingAccountId);
		assertThat(credit.get("account_id")).isEqualTo(accountId);
		assertThat(((BigDecimal) debit.get("amount"))).isEqualByComparingTo("42.50");
		assertThat(((BigDecimal) credit.get("amount"))).isEqualByComparingTo("42.50");
		assertThat(debit.get("currency")).isEqualTo("USD");
		assertThat(credit.get("currency")).isEqualTo("USD");

		BigDecimal totalDebits = (BigDecimal) debit.get("amount");
		BigDecimal totalCredits = (BigDecimal) credit.get("amount");
		assertThat(totalDebits).isEqualByComparingTo(totalCredits);
	}

	@Test
	void depositIncreasesBothCustomerAndFundingBalances() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Deposit Owner 3");
		UUID fundingAccountId = fetchUsdFundingAccountId();
		BigDecimal fundingBalanceBefore = fetchBalance(fundingAccountId);

		postDeposit(accountId, Map.of("amount", "75.25", "currency", "USD"));

		assertThat(fetchBalance(accountId)).isEqualByComparingTo("75.25");
		assertThat(fetchBalance(fundingAccountId)).isEqualByComparingTo(fundingBalanceBefore.add(new BigDecimal("75.25")));
	}

	@Test
	void secondDepositAccumulatesCustomerBalance() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Deposit Owner 4");

		postDeposit(accountId, Map.of("amount", "10.00", "currency", "USD"));
		postDeposit(accountId, Map.of("amount", "15.50", "currency", "USD"));

		assertThat(fetchBalance(accountId)).isEqualByComparingTo("25.50");
	}

	@Test
	void zeroAmountIsRejected() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Deposit Owner Zero");
		ResponseEntity<String> response = postDepositRaw(accountId, Map.of("amount", "0", "currency", "USD"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void negativeAmountIsRejected() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Deposit Owner Negative");
		ResponseEntity<String> response = postDepositRaw(accountId, Map.of("amount", "-5.00", "currency", "USD"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void missingAmountIsRejected() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Deposit Owner Missing Amount");
		ResponseEntity<String> response = postDepositRaw(accountId, Map.of("currency", "USD"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void malformedAmountIsRejected() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Deposit Owner Malformed");
		ResponseEntity<String> response = postDepositRaw(accountId, Map.of("amount", "not-a-number", "currency", "USD"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void excessivePrecisionIsRejected() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Deposit Owner Precision");
		// 5 fractional digits -- the schema and DTO both cap at 4.
		ResponseEntity<String> response = postDepositRaw(accountId, Map.of("amount", "10.12345", "currency", "USD"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void amountExceedingSupportedIntegerDigitsIsRejected() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Deposit Owner Overflow");
		// 16 integer digits -- one more than the 15 the DTO (and NUMERIC(19,4)) allow.
		ResponseEntity<String> response = postDepositRaw(accountId, Map.of("amount", "1000000000000000.00", "currency", "USD"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void nonexistentDestinationAccountIsRejected() throws SQLException {
		UUID nonexistent = UUID.randomUUID();
		ResponseEntity<String> response = postDepositRaw(nonexistent, Map.of("amount", "10.00", "currency", "USD"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void systemAccountCannotBeUsedAsPublicDepositDestination() throws SQLException {
		UUID fundingAccountId = fetchUsdFundingAccountId();
		BigDecimal balanceBefore = fetchBalance(fundingAccountId);

		ResponseEntity<String> response = postDepositRaw(fundingAccountId, Map.of("amount", "10.00", "currency", "USD"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(fetchBalance(fundingAccountId)).isEqualByComparingTo(balanceBefore);
	}

	@Test
	void nonUsdDestinationAccountIsRejected() throws SQLException {
		// The database schema is currency-format-agnostic (see V1's header
		// comment) -- only account creation enforces USD. A EUR customer
		// wallet is inserted directly here to exercise the deposit service's
		// own currency check against a row the public API could never create.
		UUID eurAccountId = insertCustomerWalletDirectly("EUR Owner", "EUR");

		ResponseEntity<String> response = postDepositRaw(eurAccountId, Map.of("amount", "10.00", "currency", "USD"));

		assertThat(response.getStatusCode().value()).isEqualTo(422);
		assertThat(fetchBalance(eurAccountId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void unknownAndProtectedJsonFieldsAreRejected() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Deposit Owner Protected Fields");

		Map<String, Object> attempt = new LinkedHashMap<>();
		attempt.put("amount", "10.00");
		attempt.put("currency", "USD");
		attempt.put("transactionId", "11111111-1111-1111-1111-111111111111");
		attempt.put("transactionType", "DEPOSIT");
		attempt.put("status", "COMPLETED");
		attempt.put("entryType", "CREDIT");
		attempt.put("fundingAccountId", "22222222-2222-2222-2222-222222222222");
		attempt.put("newBalance", "999999.00");
		attempt.put("createdAt", "2000-01-01T00:00:00Z");

		ResponseEntity<String> response = postDepositRaw(accountId, attempt);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void failedDepositsCreateNoTransactionOrEntriesAndChangeNoBalance() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Deposit Owner Failure Isolation");
		UUID fundingAccountId = fetchUsdFundingAccountId();
		long transactionsBefore = countLedgerTransactions();
		long entriesBefore = countLedgerEntries();
		BigDecimal fundingBalanceBefore = fetchBalance(fundingAccountId);

		postDepositRaw(accountId, Map.of("amount", "-1.00", "currency", "USD"));

		assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore);
		assertThat(countLedgerEntries()).isEqualTo(entriesBefore);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("0.0000");
		assertThat(fetchBalance(fundingAccountId)).isEqualByComparingTo(fundingBalanceBefore);
	}

	@Test
	void databaseOverflowMidTransactionRollsBackTheEntireDeposit() throws SQLException {
		// Deliberately drive a real database-level failure (NUMERIC(19,4)
		// field overflow) after the transaction header and both ledger
		// entries would already be scheduled for insert, to prove the whole
		// operation rolls back as one unit -- not a production failure
		// switch, just a data setup that the schema itself rejects.
		UUID accountId = createUsdCustomerAccount("Deposit Owner Rollback");
		UUID fundingAccountId = fetchUsdFundingAccountId();
		setBalanceDirectly(accountId, new BigDecimal("999999999999999.0000")); // max 15 integer digits
		long transactionsBefore = countLedgerTransactions();
		long entriesBefore = countLedgerEntries();
		BigDecimal fundingBalanceBefore = fetchBalance(fundingAccountId);

		ResponseEntity<String> response = postDepositRaw(accountId, Map.of("amount", "10.00", "currency", "USD"));

		assertThat(response.getStatusCode().is5xxServerError()).isTrue();
		assertThat(response.getBody()).doesNotContain("SQLState").doesNotContain("org.postgresql")
				.doesNotContain("org.hibernate");
		assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore);
		assertThat(countLedgerEntries()).isEqualTo(entriesBefore);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("999999999999999.0000");
		assertThat(fetchBalance(fundingAccountId)).isEqualByComparingTo(fundingBalanceBefore);
	}

	@Test
	void ledgerImmutabilityTriggersStillRejectUpdateAndDelete() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Deposit Owner Immutability");
		ResponseEntity<DepositResponse> response = postDeposit(accountId, Map.of("amount", "5.00", "currency", "USD"));
		UUID transactionId = response.getBody().transactionId();

		try (Connection connection = dataSource.getConnection()) {
			assertUpdateRejected(connection, "UPDATE ledger_transaction SET status = 'COMPLETED' WHERE id = ?", transactionId);
			assertUpdateRejected(connection, "DELETE FROM ledger_transaction WHERE id = ?", transactionId);

			UUID anyEntryId = fetchEntriesForTransaction(transactionId).get(0).get("id") instanceof UUID id ? id : null;
			assertThat(anyEntryId).isNotNull();
			assertUpdateRejected(connection, "UPDATE ledger_entry SET amount = 999.0000 WHERE id = ?", anyEntryId);
			assertUpdateRejected(connection, "DELETE FROM ledger_entry WHERE id = ?", anyEntryId);
		}
	}

	@Test
	void concurrentDepositsIntoSameWalletDoNotLoseUpdates() throws Exception {
		UUID accountId = createUsdCustomerAccount("Deposit Owner Concurrency");
		UUID fundingAccountId = fetchUsdFundingAccountId();
		BigDecimal fundingBalanceBefore = fetchBalance(fundingAccountId);

		int concurrentDeposits = 20;
		BigDecimal amountEach = new BigDecimal("5.00");
		List<UUID> transactionIds = new ArrayList<>();
		// Obtained once up front and reused by every concurrent task -- the
		// token itself carries no per-request state, and fetching it once
		// avoids 20 redundant round trips to /api/v1/auth/token racing each
		// other during the timed section below.
		String token = TestAuthSupport.customerAToken(restTemplate);
		ExecutorService executor = Executors.newFixedThreadPool(concurrentDeposits);
		try {
			List<Callable<ResponseEntity<DepositResponse>>> tasks = new ArrayList<>();
			for (int i = 0; i < concurrentDeposits; i++) {
				tasks.add(() -> {
					HttpHeaders headers = new HttpHeaders();
					headers.setContentType(MediaType.APPLICATION_JSON);
					headers.setBearerAuth(token);
					headers.set("Idempotency-Key", UUID.randomUUID().toString());
					return restTemplate.postForEntity("/api/v1/accounts/" + accountId + "/deposits",
							new HttpEntity<>(Map.of("amount", "5.00", "currency", "USD"), headers), DepositResponse.class);
				});
			}
			List<Future<ResponseEntity<DepositResponse>>> futures = executor.invokeAll(tasks, 60, TimeUnit.SECONDS);

			int successCount = 0;
			for (Future<ResponseEntity<DepositResponse>> future : futures) {
				ResponseEntity<DepositResponse> result = future.get(5, TimeUnit.SECONDS);
				assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
				transactionIds.add(result.getBody().transactionId());
				successCount++;
			}
			assertThat(successCount).isEqualTo(concurrentDeposits);
		}
		finally {
			executor.shutdownNow();
		}

		BigDecimal expectedTotal = amountEach.multiply(BigDecimal.valueOf(concurrentDeposits));
		assertThat(fetchBalance(accountId)).isEqualByComparingTo(expectedTotal);
		assertThat(fetchBalance(fundingAccountId)).isEqualByComparingTo(fundingBalanceBefore.add(expectedTotal));

		long entryCountForAccount = countLedgerEntriesForAccount(accountId);
		assertThat(entryCountForAccount).isEqualTo(concurrentDeposits);

		// Task 16: every committed deposit -- not just the aggregate
		// balance -- produced exactly one outbox_event (Task 11's
		// atomicity guarantee holding under real concurrent contention),
		// and the account's ledger-derived balance (CUSTOMER/LIABILITY:
		// credits minus debits, per docs/DATA_MODEL.md's balance formula)
		// agrees exactly with the materialized account.balance column --
		// never assumed, always recomputed from the immutable entries
		// this run actually committed.
		assertThat(transactionIds).hasSize(concurrentDeposits);
		assertThat(countOutboxEventsForTransactions(transactionIds)).isEqualTo(concurrentDeposits);
		assertThat(fetchLedgerDerivedLiabilityBalance(accountId)).isEqualByComparingTo(fetchBalance(accountId));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private HttpHeaders authHeaders() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(TestAuthSupport.customerAToken(restTemplate));
		return headers;
	}

	private UUID createUsdCustomerAccount(String ownerName) {
		ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
				"/api/v1/accounts", new HttpEntity<>(Map.of("ownerName", ownerName, "currency", "USD"), authHeaders()),
				AccountResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody().id();
	}

	private ResponseEntity<DepositResponse> postDeposit(UUID accountId, Map<String, Object> body) {
		HttpHeaders headers = authHeaders();
		headers.set("Idempotency-Key", UUID.randomUUID().toString());
		return restTemplate.postForEntity("/api/v1/accounts/" + accountId + "/deposits",
				new HttpEntity<>(body, headers), DepositResponse.class);
	}

	private ResponseEntity<String> postDepositRaw(UUID accountId, Map<String, Object> body) {
		HttpHeaders headers = authHeaders();
		headers.set("Idempotency-Key", UUID.randomUUID().toString());
		try {
			String json = JSON.writeValueAsString(body);
			return restTemplate.postForEntity("/api/v1/accounts/" + accountId + "/deposits",
					new HttpEntity<>(json, headers), String.class);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private UUID fetchUsdFundingAccountId() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT id FROM account WHERE account_category = 'SYSTEM' "
								+ "AND account_purpose = 'EXTERNAL_FUNDING' AND currency = 'USD'")) {
			try (ResultSet resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).isTrue();
				return (UUID) resultSet.getObject("id");
			}
		}
	}

	private BigDecimal fetchBalance(UUID accountId) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("SELECT balance FROM account WHERE id = ?")) {
			statement.setObject(1, accountId);
			try (ResultSet resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).isTrue();
				return resultSet.getBigDecimal("balance");
			}
		}
	}

	private void setBalanceDirectly(UUID accountId, BigDecimal balance) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("UPDATE account SET balance = ? WHERE id = ?")) {
			statement.setBigDecimal(1, balance);
			statement.setObject(2, accountId);
			statement.executeUpdate();
		}
	}

	private UUID insertCustomerWalletDirectly(String ownerName, String currency) throws SQLException {
		// customer_subject must match test-customer-a (the principal every
		// helper in this class authenticates as) so the deposit call that
		// follows still passes the Task 17 ownership check and reaches the
		// currency-mismatch logic under test, rather than failing earlier
		// with a 404 for an unowned account.
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO account (account_category, account_class, account_purpose, owner_name, currency, "
								+ "customer_subject) "
								+ "VALUES ('CUSTOMER', 'LIABILITY', 'CUSTOMER_WALLET', ?, ?, ?) RETURNING id")) {
			statement.setString(1, ownerName);
			statement.setString(2, currency);
			statement.setString(3, TestAuthSupport.CUSTOMER_A_USERNAME);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return (UUID) resultSet.getObject("id");
			}
		}
	}

	private Map.Entry<String, String> fetchTransactionTypeAndStatus(UUID transactionId) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT transaction_type, status FROM ledger_transaction WHERE id = ?")) {
			statement.setObject(1, transactionId);
			try (ResultSet resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).isTrue();
				return Map.entry(resultSet.getString("transaction_type"), resultSet.getString("status"));
			}
		}
	}

	private List<Map<String, Object>> fetchEntriesForTransaction(UUID transactionId) throws SQLException {
		List<Map<String, Object>> entries = new ArrayList<>();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT id, account_id, entry_type, amount, currency FROM ledger_entry WHERE transaction_id = ?")) {
			statement.setObject(1, transactionId);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("id", resultSet.getObject("id"));
					row.put("account_id", resultSet.getObject("account_id"));
					row.put("entry_type", resultSet.getString("entry_type"));
					row.put("amount", resultSet.getBigDecimal("amount"));
					row.put("currency", resultSet.getString("currency"));
					entries.add(row);
				}
			}
		}
		return entries;
	}

	private long countLedgerTransactions() throws SQLException {
		return countRows("SELECT COUNT(*) FROM ledger_transaction");
	}

	private long countLedgerEntries() throws SQLException {
		return countRows("SELECT COUNT(*) FROM ledger_entry");
	}

	private long countOutboxEventsForTransactions(List<UUID> transactionIds) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ANY (?)")) {
			statement.setArray(1, connection.createArrayOf("uuid", transactionIds.toArray()));
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getLong(1);
			}
		}
	}

	/**
	 * Recomputes this CUSTOMER/LIABILITY account's balance directly from
	 * its own immutable ledger_entry rows (credits minus debits, per
	 * docs/DATA_MODEL.md's balance formula for a LIABILITY account) --
	 * never trusting the materialized account.balance column, so the
	 * caller can independently verify the two agree.
	 */
	private BigDecimal fetchLedgerDerivedLiabilityBalance(UUID accountId) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END), 0) "
								+ "- COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END), 0) AS derived "
								+ "FROM ledger_entry WHERE account_id = ?")) {
			statement.setObject(1, accountId);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getBigDecimal("derived");
			}
		}
	}

	private long countLedgerEntriesForAccount(UUID accountId) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM ledger_entry WHERE account_id = ?")) {
			statement.setObject(1, accountId);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getLong(1);
			}
		}
	}

	private long countRows(String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql);
				ResultSet resultSet = statement.executeQuery()) {
			resultSet.next();
			return resultSet.getLong(1);
		}
	}

	private void assertUpdateRejected(Connection connection, String sql, UUID id) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setObject(1, id);
			statement.executeUpdate();
			throw new AssertionError("Expected immutability trigger to reject: " + sql);
		}
		catch (SQLException expected) {
			assertThat(expected.getMessage()).contains("immutable");
		}
	}

}
