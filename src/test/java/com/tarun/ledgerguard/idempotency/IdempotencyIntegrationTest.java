package com.tarun.ledgerguard.idempotency;

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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 10: Idempotency-Key handling for deposits and transfers. Exercises
 * header validation, replay semantics, cross-operation and same-operation
 * conflict detection, rollback/key-reuse-after-failure, and concurrency —
 * against a real, isolated PostgreSQL 16.4 Testcontainer, never H2 and
 * never in-memory synchronization. Concurrency assertions use bounded
 * {@code invokeAll}/{@code get} timeouts, never {@code Thread.sleep}, as
 * the correctness mechanism.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class IdempotencyIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	TestRestTemplate restTemplate;

	@Autowired
	DataSource dataSource;

	// ------------------------------------------------------------------
	// header validation
	// ------------------------------------------------------------------

	@Test
	void missingIdempotencyKeyOnDepositReturns400() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Header Missing Deposit");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/accounts/" + accountId + "/deposits",
				new HttpEntity<>(Map.of("amount", "10.00", "currency", "USD"), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void missingIdempotencyKeyOnTransferReturns400() throws SQLException {
		UUID sourceId = createUsdCustomerAccount("Header Missing Transfer Source");
		UUID destinationId = createUsdCustomerAccount("Header Missing Transfer Destination");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		Map<String, Object> body = Map.of("sourceAccountId", sourceId.toString(),
				"destinationAccountId", destinationId.toString(), "amount", "10.00", "currency", "USD");
		ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/transfers",
				new HttpEntity<>(body, headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void blankIdempotencyKeyReturns400() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Header Blank");
		ResponseEntity<String> response = postDepositRaw(accountId, "10.00", "");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void tooLongIdempotencyKeyReturns400() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Header Too Long");
		String key = "a".repeat(129);
		ResponseEntity<String> response = postDepositRaw(accountId, "10.00", key);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void maxLengthIdempotencyKeyIsAccepted() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Header Max Length");
		String key = "a".repeat(128);
		ResponseEntity<String> response = postDepositRaw(accountId, "10.00", key);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void allAllowedPunctuationCharactersAreAccepted() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Header Punctuation");
		String key = "abc.DEF_123:xyz-789";
		ResponseEntity<String> response = postDepositRaw(accountId, "10.00", key);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void disallowedCharacterInKeyReturns400() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Header Disallowed Char");
		ResponseEntity<String> response = postDepositRaw(accountId, "10.00", "has a space");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		ResponseEntity<String> response2 = postDepositRaw(accountId, "10.00", "has/slash");
		assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	// ------------------------------------------------------------------
	// deposit replay / conflict
	// ------------------------------------------------------------------

	@Test
	void identicalDepositRetryReplaysTheExactOriginalResponseAndCreatesNothingNew() throws Exception {
		UUID accountId = createUsdCustomerAccount("Deposit Replay");
		String key = UUID.randomUUID().toString();
		long transactionsBefore = countLedgerTransactions();
		long entriesBefore = countLedgerEntries();

		ResponseEntity<String> first = postDepositRaw(accountId, "25.00", key);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		ResponseEntity<String> retry = postDepositRaw(accountId, "25.00", key);
		assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(retry.getBody()).isEqualTo(first.getBody());

		assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore + 1);
		assertThat(countLedgerEntries()).isEqualTo(entriesBefore + 2);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("25.00");
		assertThat(countIdempotencyRows(key)).isEqualTo(1);
	}

	@Test
	void numericallyEquivalentFormattingReplaysTheSameDeposit() throws Exception {
		UUID accountId = createUsdCustomerAccount("Deposit Replay Formatting");
		String key = UUID.randomUUID().toString();

		ResponseEntity<String> first = postDepositRaw(accountId, "100", key);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		ResponseEntity<String> retry = postDepositRaw(accountId, "100.00", key);
		assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(retry.getBody()).isEqualTo(first.getBody());

		assertThat(fetchBalance(accountId)).isEqualByComparingTo("100.00");
	}

	@Test
	void reusingDepositKeyWithDifferentAmountReturns409AndChangesNothing() throws Exception {
		UUID accountId = createUsdCustomerAccount("Deposit Conflict Amount");
		String key = UUID.randomUUID().toString();

		postDepositRaw(accountId, "10.00", key);
		long transactionsBefore = countLedgerTransactions();

		ResponseEntity<String> conflict = postDepositRaw(accountId, "20.00", key);

		assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("10.00");
	}

	@Test
	void reusingDepositKeyWithDifferentAccountReturns409AndChangesNothing() throws Exception {
		UUID accountId = createUsdCustomerAccount("Deposit Conflict Account A");
		UUID otherAccountId = createUsdCustomerAccount("Deposit Conflict Account B");
		String key = UUID.randomUUID().toString();

		postDepositRaw(accountId, "10.00", key);

		ResponseEntity<String> conflict = postDepositRaw(otherAccountId, "10.00", key);

		assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(fetchBalance(otherAccountId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void reusingDepositKeyWithDifferentCurrencyReturns409NotAValidationError() throws Exception {
		UUID accountId = createUsdCustomerAccount("Deposit Conflict Currency");
		String key = UUID.randomUUID().toString();

		postDepositRaw(accountId, "10.00", key);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Idempotency-Key", key);
		ResponseEntity<String> conflict = restTemplate.postForEntity("/api/v1/accounts/" + accountId + "/deposits",
				new HttpEntity<>(Map.of("amount", "10.00", "currency", "EUR"), headers), String.class);

		assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
	}

	// ------------------------------------------------------------------
	// transfer replay / conflict
	// ------------------------------------------------------------------

	@Test
	void identicalTransferRetryReplaysTheExactOriginalResponseAndCreatesNothingNew() throws Exception {
		UUID sourceId = createFundedAccount("Transfer Replay Source", "100.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Replay Destination");
		String key = UUID.randomUUID().toString();
		long transactionsBefore = countLedgerTransactions();

		ResponseEntity<String> first = postTransferRaw(sourceId, destinationId, "40.00", key);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		ResponseEntity<String> retry = postTransferRaw(sourceId, destinationId, "40.00", key);
		assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(retry.getBody()).isEqualTo(first.getBody());

		assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore + 1);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("60.00");
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("40.00");
	}

	@Test
	void reusingTransferKeyWithDifferentDestinationReturns409AndChangesNothing() throws Exception {
		UUID sourceId = createFundedAccount("Transfer Conflict Source", "100.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Conflict Destination A");
		UUID otherDestinationId = createUsdCustomerAccount("Transfer Conflict Destination B");
		String key = UUID.randomUUID().toString();

		postTransferRaw(sourceId, destinationId, "10.00", key);

		ResponseEntity<String> conflict = postTransferRaw(sourceId, otherDestinationId, "10.00", key);

		assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("90.00");
		assertThat(fetchBalance(otherDestinationId)).isEqualByComparingTo("0.0000");
	}

	// ------------------------------------------------------------------
	// cross-operation conflict
	// ------------------------------------------------------------------

	@Test
	void depositKeyReusedAgainstTransferReturns409AndChangesNothing() throws Exception {
		UUID accountId = createFundedAccount("Cross Op Deposit Then Transfer Source", "50.00");
		UUID destinationId = createUsdCustomerAccount("Cross Op Deposit Then Transfer Destination");
		String key = UUID.randomUUID().toString();

		postDepositRaw(accountId, "10.00", key);
		BigDecimal balanceAfterDeposit = fetchBalance(accountId);

		ResponseEntity<String> conflict = postTransferRaw(accountId, destinationId, "10.00", key);

		assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo(balanceAfterDeposit);
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void transferKeyReusedAgainstDepositReturns409AndChangesNothing() throws Exception {
		UUID sourceId = createFundedAccount("Cross Op Transfer Then Deposit Source", "50.00");
		UUID destinationId = createUsdCustomerAccount("Cross Op Transfer Then Deposit Destination");
		String key = UUID.randomUUID().toString();

		postTransferRaw(sourceId, destinationId, "10.00", key);
		BigDecimal destinationBalanceAfterTransfer = fetchBalance(destinationId);

		ResponseEntity<String> conflict = postDepositRaw(destinationId, "10.00", key);

		assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo(destinationBalanceAfterTransfer);
	}

	// ------------------------------------------------------------------
	// rollback / key not consumed by a failed attempt
	// ------------------------------------------------------------------

	@Test
	void failedDepositAgainstNonexistentAccountDoesNotConsumeTheKey() throws Exception {
		UUID nonexistent = UUID.randomUUID();
		UUID realAccountId = createUsdCustomerAccount("Rollback Retry Deposit");
		String key = UUID.randomUUID().toString();

		ResponseEntity<String> failed = postDepositRaw(nonexistent, "10.00", key);
		assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(countIdempotencyRows(key)).isEqualTo(0);

		ResponseEntity<String> retried = postDepositRaw(realAccountId, "10.00", key);
		assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(fetchBalance(realAccountId)).isEqualByComparingTo("10.00");
	}

	@Test
	void failedTransferDueToInsufficientFundsDoesNotConsumeTheKeyAndCanSucceedAfterFunding() throws Exception {
		UUID sourceId = createUsdCustomerAccount("Rollback Retry Transfer Source");
		UUID destinationId = createUsdCustomerAccount("Rollback Retry Transfer Destination");
		String key = UUID.randomUUID().toString();

		ResponseEntity<String> failed = postTransferRaw(sourceId, destinationId, "10.00", key);
		assertThat(failed.getStatusCode().value()).isEqualTo(422);
		assertThat(countIdempotencyRows(key)).isEqualTo(0);

		fundAccount(sourceId, "10.00");

		ResponseEntity<String> retried = postTransferRaw(sourceId, destinationId, "10.00", key);
		assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("10.00");
	}

	@Test
	void databaseFailureRollsBackBothTheDepositAndTheIdempotencyRecord() throws Exception {
		UUID accountId = createUsdCustomerAccount("Rollback DB Failure Deposit");
		setBalanceDirectly(accountId, new BigDecimal("999999999999999.0000")); // max 15 integer digits
		String key = UUID.randomUUID().toString();
		long transactionsBefore = countLedgerTransactions();

		ResponseEntity<String> failed = postDepositRaw(accountId, "10.00", key);
		assertThat(failed.getStatusCode().is5xxServerError()).isTrue();
		assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore);
		assertThat(countIdempotencyRows(key)).isEqualTo(0);

		setBalanceDirectly(accountId, BigDecimal.ZERO);
		ResponseEntity<String> retried = postDepositRaw(accountId, "10.00", key);
		assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	// ------------------------------------------------------------------
	// concurrency (PostgreSQL advisory lock, bounded waits, no Thread.sleep)
	// ------------------------------------------------------------------

	@Test
	void simultaneousIdenticalDepositRequestsCommitExactlyOnce() throws Exception {
		UUID accountId = createUsdCustomerAccount("Concurrent Identical Deposit");
		String key = UUID.randomUUID().toString();
		long transactionsBefore = countLedgerTransactions();

		int concurrentRequests = 15;
		ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
		try {
			List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
			for (int i = 0; i < concurrentRequests; i++) {
				tasks.add(() -> postDepositRaw(accountId, "5.00", key));
			}
			List<Future<ResponseEntity<String>>> futures = executor.invokeAll(tasks, 60, TimeUnit.SECONDS);

			List<String> bodies = new ArrayList<>();
			for (Future<ResponseEntity<String>> future : futures) {
				ResponseEntity<String> result = future.get(5, TimeUnit.SECONDS);
				assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
				bodies.add(result.getBody());
			}
			assertThat(bodies).allMatch(body -> body.equals(bodies.get(0)));
		}
		finally {
			executor.shutdown();
		}

		assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore + 1);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("5.00");
		assertThat(countIdempotencyRows(key)).isEqualTo(1);
	}

	@Test
	void simultaneousConflictingDepositRequestsWithTheSameKeyProduceExactlyOneWinner() throws Exception {
		UUID accountId = createUsdCustomerAccount("Concurrent Conflicting Deposit");
		String key = UUID.randomUUID().toString();

		int perGroup = 8;
		ExecutorService executor = Executors.newFixedThreadPool(perGroup * 2);
		try {
			List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
			for (int i = 0; i < perGroup; i++) {
				tasks.add(() -> postDepositRaw(accountId, "10.00", key));
				tasks.add(() -> postDepositRaw(accountId, "20.00", key));
			}
			List<Future<ResponseEntity<String>>> futures = executor.invokeAll(tasks, 60, TimeUnit.SECONDS);

			List<ResponseEntity<String>> results = new ArrayList<>();
			for (Future<ResponseEntity<String>> future : futures) {
				results.add(future.get(5, TimeUnit.SECONDS));
			}

			List<ResponseEntity<String>> created = results.stream()
					.filter(r -> r.getStatusCode() == HttpStatus.CREATED)
					.collect(Collectors.toList());
			List<ResponseEntity<String>> conflicted = results.stream()
					.filter(r -> r.getStatusCode() == HttpStatus.CONFLICT)
					.collect(Collectors.toList());

			assertThat(created.size() + conflicted.size()).isEqualTo(perGroup * 2);
			assertThat(created).isNotEmpty();
			assertThat(created).allMatch(r -> r.getBody().equals(created.get(0).getBody()));
		}
		finally {
			executor.shutdown();
		}

		assertThat(countIdempotencyRows(key)).isEqualTo(1);
		BigDecimal finalBalance = fetchBalance(accountId);
		assertThat(finalBalance).isIn(new BigDecimal("10.0000"), new BigDecimal("20.0000"));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private UUID createUsdCustomerAccount(String ownerName) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map> response = restTemplate.postForEntity(
				"/api/v1/accounts", new HttpEntity<>(Map.of("ownerName", ownerName, "currency", "USD"), headers),
				Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return UUID.fromString((String) response.getBody().get("id"));
	}

	private UUID createFundedAccount(String ownerName, String amount) throws SQLException {
		UUID accountId = createUsdCustomerAccount(ownerName);
		fundAccount(accountId, amount);
		return accountId;
	}

	private void fundAccount(UUID accountId, String amount) {
		ResponseEntity<String> response = postDepositRaw(accountId, amount, UUID.randomUUID().toString());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private ResponseEntity<String> postDepositRaw(UUID accountId, String amount, String idempotencyKey) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Idempotency-Key", idempotencyKey);
		return restTemplate.postForEntity("/api/v1/accounts/" + accountId + "/deposits",
				new HttpEntity<>(Map.of("amount", amount, "currency", "USD"), headers), String.class);
	}

	private ResponseEntity<String> postTransferRaw(UUID sourceId, UUID destinationId, String amount, String idempotencyKey) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Idempotency-Key", idempotencyKey);
		Map<String, Object> body = Map.of("sourceAccountId", sourceId.toString(),
				"destinationAccountId", destinationId.toString(), "amount", amount, "currency", "USD");
		return restTemplate.postForEntity("/api/v1/transfers", new HttpEntity<>(body, headers), String.class);
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

	private long countLedgerTransactions() throws SQLException {
		return countRows("SELECT COUNT(*) FROM ledger_transaction");
	}

	private long countLedgerEntries() throws SQLException {
		return countRows("SELECT COUNT(*) FROM ledger_entry");
	}

	private long countIdempotencyRows(String idempotencyKey) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM idempotency_key WHERE idempotency_key = ?")) {
			statement.setString(1, idempotencyKey);
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

}
