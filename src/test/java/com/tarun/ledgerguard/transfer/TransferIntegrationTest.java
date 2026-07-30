package com.tarun.ledgerguard.transfer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarun.ledgerguard.account.dto.AccountResponse;
import com.tarun.ledgerguard.transfer.dto.TransferResponse;
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
 * Exercises transfers (Task 5) at the HTTP boundary and verifies persisted
 * database state directly via JDBC, against a real, isolated PostgreSQL
 * 16.4 Testcontainer that runs the Flyway migrations from scratch. No
 * mocks are used for persistence behavior; concurrency is exercised
 * against real PostgreSQL row locking, not Java-only synchronization.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TransferIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	TestRestTemplate restTemplate;

	@Autowired
	DataSource dataSource;

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	void validTransferReturnsDocumentedSuccessAndResponse() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Source 1", "100.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Destination 1");

		ResponseEntity<TransferResponse> response = postTransfer(sourceId, destinationId, "40.00");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		TransferResponse body = response.getBody();
		assertThat(body).isNotNull();
		assertThat(body.transactionId()).isNotNull();
		assertThat(body.sourceAccountId()).isEqualTo(sourceId);
		assertThat(body.destinationAccountId()).isEqualTo(destinationId);
		assertThat(body.amount()).isEqualByComparingTo("40.00");
		assertThat(body.currency()).isEqualTo("USD");
		assertThat(body.createdAt()).isNotNull();
	}

	@Test
	void validTransferCreatesOneBalancedTransactionWithTwoEntries() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Source 2", "100.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Destination 2");

		ResponseEntity<TransferResponse> response = postTransfer(sourceId, destinationId, "30.00");
		UUID transactionId = response.getBody().transactionId();

		assertThat(fetchTransactionTypeAndStatus(transactionId)).isEqualTo(Map.entry("TRANSFER", "COMPLETED"));

		List<Map<String, Object>> entries = fetchEntriesForTransaction(transactionId);
		assertThat(entries).hasSize(2);

		Map<String, Object> debit = entries.stream().filter(e -> e.get("entry_type").equals("DEBIT")).findFirst().orElseThrow();
		Map<String, Object> credit = entries.stream().filter(e -> e.get("entry_type").equals("CREDIT")).findFirst().orElseThrow();

		assertThat(debit.get("account_id")).isEqualTo(sourceId);
		assertThat(credit.get("account_id")).isEqualTo(destinationId);
		assertThat(((BigDecimal) debit.get("amount"))).isEqualByComparingTo("30.00");
		assertThat(((BigDecimal) credit.get("amount"))).isEqualByComparingTo("30.00");
		assertThat(debit.get("currency")).isEqualTo("USD");
		assertThat(credit.get("currency")).isEqualTo("USD");
		assertThat(((BigDecimal) debit.get("amount"))).isEqualByComparingTo((BigDecimal) credit.get("amount"));
	}

	@Test
	void transferDecreasesSourceAndIncreasesDestinationPreservingCombinedTotal() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Source 3", "100.00");
		UUID destinationId = createFundedAccount("Transfer Destination 3", "20.00");
		BigDecimal combinedBefore = fetchBalance(sourceId).add(fetchBalance(destinationId));

		postTransfer(sourceId, destinationId, "25.00");

		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("75.00");
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("45.00");
		BigDecimal combinedAfter = fetchBalance(sourceId).add(fetchBalance(destinationId));
		assertThat(combinedAfter).isEqualByComparingTo(combinedBefore);
	}

	@Test
	void sequentialTransfersAccumulateCorrectly() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Source 4", "100.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Destination 4");

		postTransfer(sourceId, destinationId, "10.00");
		postTransfer(sourceId, destinationId, "5.50");
		postTransfer(sourceId, destinationId, "1.25");

		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("83.25");
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("16.75");
	}

	@Test
	void transferOfFullBalanceLeavesSourceExactlyZero() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Source 5", "50.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Destination 5");

		ResponseEntity<TransferResponse> response = postTransfer(sourceId, destinationId, "50.00");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("0.0000");
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("50.00");
	}

	@Test
	void zeroAmountIsRejected() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Zero Source", "10.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Zero Destination");
		ResponseEntity<String> response = postTransferRaw(sourceId, destinationId, "0");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("10.00");
	}

	@Test
	void negativeAmountIsRejected() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Negative Source", "10.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Negative Destination");
		ResponseEntity<String> response = postTransferRaw(sourceId, destinationId, "-5.00");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("10.00");
	}

	@Test
	void missingAmountIsRejected() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Missing Amount Source", "10.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Missing Amount Destination");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceAccountId", sourceId.toString());
		body.put("destinationAccountId", destinationId.toString());
		body.put("currency", "USD");
		ResponseEntity<String> response = postTransferRawBody(body);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void malformedAmountIsRejected() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Malformed Source", "10.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Malformed Destination");
		ResponseEntity<String> response = postTransferRaw(sourceId, destinationId, "not-a-number");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void excessivePrecisionIsRejected() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Precision Source", "10.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Precision Destination");
		ResponseEntity<String> response = postTransferRaw(sourceId, destinationId, "1.23456");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("10.00");
	}

	@Test
	void amountExceedingSupportedIntegerDigitsIsRejected() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Overflow Source", "10.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Overflow Destination");
		ResponseEntity<String> response = postTransferRaw(sourceId, destinationId, "1000000000000000.00");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("10.00");
	}

	@Test
	void missingSourceIdIsRejected() throws SQLException {
		UUID destinationId = createUsdCustomerAccount("Transfer No Source Destination");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("destinationAccountId", destinationId.toString());
		body.put("amount", "10.00");
		body.put("currency", "USD");
		ResponseEntity<String> response = postTransferRawBody(body);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void missingDestinationIdIsRejected() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer No Destination Source", "10.00");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceAccountId", sourceId.toString());
		body.put("amount", "10.00");
		body.put("currency", "USD");
		ResponseEntity<String> response = postTransferRawBody(body);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("10.00");
	}

	@Test
	void nonexistentSourceAccountIsRejected() throws SQLException {
		UUID destinationId = createUsdCustomerAccount("Transfer Nonexistent Source Destination");
		ResponseEntity<String> response = postTransferRaw(UUID.randomUUID(), destinationId, "10.00");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void nonexistentDestinationAccountIsRejected() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Nonexistent Destination Source", "10.00");
		ResponseEntity<String> response = postTransferRaw(sourceId, UUID.randomUUID(), "10.00");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("10.00");
	}

	@Test
	void sameAccountTransferIsRejected() throws SQLException {
		UUID accountId = createFundedAccount("Transfer Same Account", "10.00");
		ResponseEntity<String> response = postTransferRaw(accountId, accountId, "5.00");
		assertThat(response.getStatusCode().value()).isEqualTo(422);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("10.00");
	}

	@Test
	void insufficientFundsIsRejected() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Insufficient Source", "10.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Insufficient Destination");

		ResponseEntity<String> response = postTransferRaw(sourceId, destinationId, "10.01");

		assertThat(response.getStatusCode().value()).isEqualTo(422);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("10.00");
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void systemAccountCannotBeUsedAsSource() throws SQLException {
		UUID fundingAccountId = fetchUsdFundingAccountId();
		UUID destinationId = createUsdCustomerAccount("Transfer System Source Destination");
		BigDecimal fundingBalanceBefore = fetchBalance(fundingAccountId);

		ResponseEntity<String> response = postTransferRaw(fundingAccountId, destinationId, "10.00");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(fetchBalance(fundingAccountId)).isEqualByComparingTo(fundingBalanceBefore);
	}

	@Test
	void systemAccountCannotBeUsedAsDestination() throws SQLException {
		UUID fundingAccountId = fetchUsdFundingAccountId();
		UUID sourceId = createFundedAccount("Transfer System Destination Source", "10.00");
		BigDecimal fundingBalanceBefore = fetchBalance(fundingAccountId);

		ResponseEntity<String> response = postTransferRaw(sourceId, fundingAccountId, "10.00");

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("10.00");
		assertThat(fetchBalance(fundingAccountId)).isEqualByComparingTo(fundingBalanceBefore);
	}

	@Test
	void nonUsdSourceAccountIsRejected() throws SQLException {
		UUID eurSourceId = insertCustomerWalletDirectly("EUR Transfer Source", "EUR", "50.0000");
		UUID destinationId = createUsdCustomerAccount("Transfer Non-USD Source Destination");

		ResponseEntity<String> response = postTransferRaw(eurSourceId, destinationId, "10.00");

		assertThat(response.getStatusCode().value()).isEqualTo(422);
		assertThat(fetchBalance(eurSourceId)).isEqualByComparingTo("50.0000");
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void nonUsdDestinationAccountIsRejected() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Non-USD Destination Source", "50.00");
		UUID eurDestinationId = insertCustomerWalletDirectly("EUR Transfer Destination", "EUR", "0.0000");

		ResponseEntity<String> response = postTransferRaw(sourceId, eurDestinationId, "10.00");

		assertThat(response.getStatusCode().value()).isEqualTo(422);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("50.00");
		assertThat(fetchBalance(eurDestinationId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void unknownAndProtectedJsonFieldsAreRejected() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Protected Fields Source", "10.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Protected Fields Destination");

		Map<String, Object> attempt = new LinkedHashMap<>();
		attempt.put("sourceAccountId", sourceId.toString());
		attempt.put("destinationAccountId", destinationId.toString());
		attempt.put("amount", "5.00");
		attempt.put("currency", "USD");
		attempt.put("transactionId", "11111111-1111-1111-1111-111111111111");
		attempt.put("transactionType", "TRANSFER");
		attempt.put("status", "COMPLETED");
		attempt.put("entryType", "DEBIT");
		attempt.put("newBalance", "999999.00");
		attempt.put("createdAt", "2000-01-01T00:00:00Z");

		ResponseEntity<String> response = postTransferRawBody(attempt);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("10.00");
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void failedTransfersCreateNoTransactionOrEntriesAndChangeNoBalance() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Failure Isolation Source", "5.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Failure Isolation Destination");
		long transactionsBefore = countLedgerTransactions();
		long entriesBefore = countLedgerEntries();

		postTransferRaw(sourceId, destinationId, "5.01"); // insufficient funds

		assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore);
		assertThat(countLedgerEntries()).isEqualTo(entriesBefore);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("5.00");
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void databaseOverflowMidTransactionRollsBackTheEntireTransfer() throws SQLException {
		// Deliberately drive a real database-level failure (NUMERIC(19,4)
		// field overflow) after the transaction header and both ledger
		// entries would already be scheduled for insert, to prove the whole
		// operation rolls back as one unit -- not a production failure
		// switch, just a data setup that the schema itself rejects.
		UUID sourceId = createFundedAccount("Transfer Rollback Source", "50.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Rollback Destination");
		setBalanceDirectly(destinationId, new BigDecimal("999999999999999.0000")); // max 15 integer digits
		long transactionsBefore = countLedgerTransactions();
		long entriesBefore = countLedgerEntries();

		ResponseEntity<String> response = postTransferRaw(sourceId, destinationId, "10.00");

		assertThat(response.getStatusCode().is5xxServerError()).isTrue();
		assertThat(response.getBody()).doesNotContain("SQLState").doesNotContain("org.postgresql")
				.doesNotContain("org.hibernate");
		assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore);
		assertThat(countLedgerEntries()).isEqualTo(entriesBefore);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("50.00");
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("999999999999999.0000");
	}

	@Test
	void ledgerImmutabilityTriggersStillRejectUpdateAndDelete() throws SQLException {
		UUID sourceId = createFundedAccount("Transfer Immutability Source", "10.00");
		UUID destinationId = createUsdCustomerAccount("Transfer Immutability Destination");
		ResponseEntity<TransferResponse> response = postTransfer(sourceId, destinationId, "5.00");
		UUID transactionId = response.getBody().transactionId();

		try (Connection connection = dataSource.getConnection()) {
			assertUpdateRejected(connection, "UPDATE ledger_transaction SET status = 'COMPLETED' WHERE id = ?", transactionId);
			assertUpdateRejected(connection, "DELETE FROM ledger_transaction WHERE id = ?", transactionId);

			Object entryIdObj = fetchEntriesForTransaction(transactionId).get(0).get("id");
			UUID entryId = (UUID) entryIdObj;
			assertUpdateRejected(connection, "UPDATE ledger_entry SET amount = 999.0000 WHERE id = ?", entryId);
			assertUpdateRejected(connection, "DELETE FROM ledger_entry WHERE id = ?", entryId);
		}
	}

	@Test
	void depositFollowedByTransferProducesExpectedBalances() throws SQLException {
		UUID fundingAccountId = fetchUsdFundingAccountId();
		BigDecimal fundingBalanceBefore = fetchBalance(fundingAccountId);

		UUID sourceId = createUsdCustomerAccount("Deposit Then Transfer Source");
		UUID destinationId = createUsdCustomerAccount("Deposit Then Transfer Destination");

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<String> depositResponse = restTemplate.postForEntity(
				"/api/v1/accounts/" + sourceId + "/deposits",
				new HttpEntity<>(Map.of("amount", "100.00", "currency", "USD"), headers), String.class);
		assertThat(depositResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("100.00");
		assertThat(fetchBalance(fundingAccountId)).isEqualByComparingTo(fundingBalanceBefore.add(new BigDecimal("100.00")));

		postTransfer(sourceId, destinationId, "60.00");

		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("40.00");
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("60.00");
		// Transfer must not touch EXTERNAL_FUNDING at all.
		assertThat(fetchBalance(fundingAccountId)).isEqualByComparingTo(fundingBalanceBefore.add(new BigDecimal("100.00")));
	}

	@Test
	void concurrentTransfersFromOneSourceDoNotOverspend() throws Exception {
		UUID sourceId = createFundedAccount("Concurrent Overspend Source", "100.00");
		UUID destinationId = createUsdCustomerAccount("Concurrent Overspend Destination");

		int concurrentTransfers = 20;
		BigDecimal amountEach = new BigDecimal("10.00"); // only 10 of 20 can succeed
		ExecutorService executor = Executors.newFixedThreadPool(concurrentTransfers);
		try {
			List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
			for (int i = 0; i < concurrentTransfers; i++) {
				tasks.add(() -> postTransferRaw(sourceId, destinationId, "10.00"));
			}
			List<Future<ResponseEntity<String>>> futures = executor.invokeAll(tasks, 60, TimeUnit.SECONDS);

			int successCount = 0;
			int rejectedCount = 0;
			for (Future<ResponseEntity<String>> future : futures) {
				ResponseEntity<String> result = future.get(5, TimeUnit.SECONDS);
				if (result.getStatusCode() == HttpStatus.CREATED) {
					successCount++;
				}
				else {
					assertThat(result.getStatusCode().value()).isEqualTo(422);
					rejectedCount++;
				}
			}
			assertThat(successCount).isEqualTo(10);
			assertThat(rejectedCount).isEqualTo(10);
		}
		finally {
			executor.shutdown();
		}

		BigDecimal expectedTransferred = amountEach.multiply(BigDecimal.valueOf(10));
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo(new BigDecimal("100.00").subtract(expectedTransferred));
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo(expectedTransferred);
		assertThat(fetchBalance(sourceId)).isGreaterThanOrEqualTo(BigDecimal.ZERO);
	}

	@Test
	void concurrentOppositeDirectionTransfersCompleteWithoutLostUpdatesOrDeadlock() throws Exception {
		UUID accountA = createFundedAccount("Concurrent Opposite A", "100.00");
		UUID accountB = createFundedAccount("Concurrent Opposite B", "100.00");

		int pairsEachDirection = 10;
		BigDecimal amountEach = new BigDecimal("5.00");
		ExecutorService executor = Executors.newFixedThreadPool(pairsEachDirection * 2);
		try {
			List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
			for (int i = 0; i < pairsEachDirection; i++) {
				tasks.add(() -> postTransferRaw(accountA, accountB, "5.00"));
				tasks.add(() -> postTransferRaw(accountB, accountA, "5.00"));
			}
			List<Future<ResponseEntity<String>>> futures = executor.invokeAll(tasks, 60, TimeUnit.SECONDS);

			for (Future<ResponseEntity<String>> future : futures) {
				ResponseEntity<String> result = future.get(5, TimeUnit.SECONDS);
				assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
			}
		}
		finally {
			executor.shutdown();
		}

		// Equal amounts moved in both directions in equal counts -- net zero change.
		assertThat(fetchBalance(accountA)).isEqualByComparingTo("100.00");
		assertThat(fetchBalance(accountB)).isEqualByComparingTo("100.00");
		// +1 each: createFundedAccount's own opening deposit already wrote one
		// CREDIT ledger_entry for that account before the transfer batch ran.
		assertThat(countLedgerEntriesForAccount(accountA)).isEqualTo(pairsEachDirection * 2 + 1);
		assertThat(countLedgerEntriesForAccount(accountB)).isEqualTo(pairsEachDirection * 2 + 1);
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private UUID createUsdCustomerAccount(String ownerName) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
				"/api/v1/accounts", new HttpEntity<>(Map.of("ownerName", ownerName, "currency", "USD"), headers),
				AccountResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody().id();
	}

	private UUID createFundedAccount(String ownerName, String depositAmount) {
		UUID accountId = createUsdCustomerAccount(ownerName);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<String> response = restTemplate.postForEntity(
				"/api/v1/accounts/" + accountId + "/deposits",
				new HttpEntity<>(Map.of("amount", depositAmount, "currency", "USD"), headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return accountId;
	}

	private ResponseEntity<TransferResponse> postTransfer(UUID sourceId, UUID destinationId, String amount) {
		Map<String, Object> body = Map.of(
				"sourceAccountId", sourceId.toString(),
				"destinationAccountId", destinationId.toString(),
				"amount", amount,
				"currency", "USD");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return restTemplate.postForEntity("/api/v1/transfers", new HttpEntity<>(body, headers), TransferResponse.class);
	}

	private ResponseEntity<String> postTransferRaw(UUID sourceId, UUID destinationId, String amount) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceAccountId", sourceId.toString());
		body.put("destinationAccountId", destinationId.toString());
		body.put("amount", amount);
		body.put("currency", "USD");
		return postTransferRawBody(body);
	}

	private ResponseEntity<String> postTransferRawBody(Map<String, Object> body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		try {
			String json = JSON.writeValueAsString(body);
			return restTemplate.postForEntity("/api/v1/transfers", new HttpEntity<>(json, headers), String.class);
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

	private UUID insertCustomerWalletDirectly(String ownerName, String currency, String balance) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO account (account_category, account_class, account_purpose, owner_name, currency, balance) "
								+ "VALUES ('CUSTOMER', 'LIABILITY', 'CUSTOMER_WALLET', ?, ?, ?) RETURNING id")) {
			statement.setString(1, ownerName);
			statement.setString(2, currency);
			statement.setBigDecimal(3, new BigDecimal(balance));
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
