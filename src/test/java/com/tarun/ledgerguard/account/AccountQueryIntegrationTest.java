package com.tarun.ledgerguard.account;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarun.ledgerguard.account.dto.AccountBalanceResponse;
import com.tarun.ledgerguard.account.dto.AccountResponse;
import com.tarun.ledgerguard.account.dto.TransactionHistoryItem;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the account balance and transaction-history read endpoints
 * (Task 6) at the HTTP boundary, and confirms via direct JDBC that GET
 * requests never write anything, against a real, isolated PostgreSQL 16.4
 * Testcontainer that runs the Flyway migrations from scratch. No mocks are
 * used for persistence behavior.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountQueryIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	TestRestTemplate restTemplate;

	@Autowired
	DataSource dataSource;

	// Configured to deserialize JSON numbers into BigDecimal rather than
	// Double when reading into a generic Map<String,Object> -- needed here
	// because these tests read pagination envelopes generically (the
	// envelope shape itself, not just one typed field, is under test), and
	// a plain ObjectMapper would otherwise silently lose decimal precision
	// through Double for the "amount" field.
	private static final ObjectMapper JSON = new ObjectMapper()
			.enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);

	// ------------------------------------------------------------------
	// balance
	// ------------------------------------------------------------------

	@Test
	void newAccountCanBeRetrievedWithExactZeroBalance() {
		UUID accountId = createUsdCustomerAccount("Balance Owner New");

		ResponseEntity<AccountBalanceResponse> response = getBalance(accountId);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		AccountBalanceResponse body = response.getBody();
		assertThat(body).isNotNull();
		assertThat(body.accountId()).isEqualTo(accountId);
		assertThat(body.balance()).isEqualByComparingTo("0.0000");
		assertThat(body.currency()).isEqualTo("USD");
	}

	@Test
	void balanceResponseContainsExactlyApprovedFields() throws Exception {
		UUID accountId = createUsdCustomerAccount("Balance Owner Fields");

		ResponseEntity<String> raw = getBalanceRaw(accountId);

		assertThat(raw.getStatusCode()).isEqualTo(HttpStatus.OK);
		Map<String, Object> body = readJsonObject(raw.getBody());
		assertThat(body.keySet()).containsExactlyInAnyOrder("accountId", "balance", "currency");
	}

	@Test
	void depositIncreasesReturnedBalanceByExactAmount() {
		UUID accountId = createUsdCustomerAccount("Balance Owner Deposit");

		deposit(accountId, "40.25");

		assertThat(getBalance(accountId).getBody().balance()).isEqualByComparingTo("40.25");
	}

	@Test
	void transferDecreasesSourceAndIncreasesDestinationReturnedBalance() {
		UUID sourceId = createUsdCustomerAccount("Balance Owner Transfer Source");
		UUID destinationId = createUsdCustomerAccount("Balance Owner Transfer Destination");
		deposit(sourceId, "100.00");

		transfer(sourceId, destinationId, "30.00");

		assertThat(getBalance(sourceId).getBody().balance()).isEqualByComparingTo("70.00");
		assertThat(getBalance(destinationId).getBody().balance()).isEqualByComparingTo("30.00");
	}

	@Test
	void multipleDepositsAndTransfersProduceExpectedFinalBalances() {
		UUID accountA = createUsdCustomerAccount("Balance Owner Multi A");
		UUID accountB = createUsdCustomerAccount("Balance Owner Multi B");

		deposit(accountA, "100.00");
		deposit(accountA, "50.00");
		transfer(accountA, accountB, "60.00");
		deposit(accountB, "10.00");
		transfer(accountB, accountA, "5.00");

		// A: +100 +50 -60 +5 = 95.00 ; B: +60 +10 -5 = 65.00
		assertThat(getBalance(accountA).getBody().balance()).isEqualByComparingTo("95.00");
		assertThat(getBalance(accountB).getBody().balance()).isEqualByComparingTo("65.00");
	}

	@Test
	void monetaryValueRetainsExactScale() {
		UUID accountId = createUsdCustomerAccount("Balance Owner Scale");
		deposit(accountId, "12.34");

		BigDecimal balance = getBalance(accountId).getBody().balance();

		assertThat(balance.scale()).isEqualTo(4);
		assertThat(balance).isEqualByComparingTo("12.3400");
	}

	@Test
	void nonexistentAccountBalanceReturns404() {
		ResponseEntity<String> response = getBalanceRaw(UUID.randomUUID());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void malformedUuidForBalanceReturns400() {
		ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/accounts/not-a-uuid/balance", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void systemAccountBalanceIsHiddenAs404() throws SQLException {
		UUID fundingAccountId = fetchUsdFundingAccountId();
		ResponseEntity<String> response = getBalanceRaw(fundingAccountId);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void getBalanceCreatesNoLedgerRowsAndChangesNoBalance() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Balance Owner Read Isolation");
		deposit(accountId, "20.00");
		long transactionsBefore = countLedgerTransactions();
		long entriesBefore = countLedgerEntries();

		getBalance(accountId);
		getBalance(accountId);
		getBalance(accountId);

		assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore);
		assertThat(countLedgerEntries()).isEqualTo(entriesBefore);
		assertThat(getBalance(accountId).getBody().balance()).isEqualByComparingTo("20.00");
	}

	// ------------------------------------------------------------------
	// transaction history
	// ------------------------------------------------------------------

	@Test
	void newAccountHasEmptyHistory() {
		UUID accountId = createUsdCustomerAccount("History Owner New");

		Map<String, Object> page = getHistory(accountId, null, null);

		assertThat((List<?>) page.get("content")).isEmpty();
		assertThat(page.get("page")).isEqualTo(0);
		assertThat(page.get("size")).isEqualTo(20);
		assertThat(((Number) page.get("totalElements")).longValue()).isEqualTo(0);
		assertThat(page.get("totalPages")).isEqualTo(0);
	}

	@Test
	void depositAppearsInCustomerHistoryAsCredit() {
		UUID accountId = createUsdCustomerAccount("History Owner Deposit");
		UUID transactionId = deposit(accountId, "55.00").transactionId();

		List<Map<String, Object>> content = historyContent(accountId, null, null);

		assertThat(content).hasSize(1);
		Map<String, Object> item = content.get(0);
		assertThat(item.get("transactionId")).isEqualTo(transactionId.toString());
		assertThat(item.get("entryType")).isEqualTo("CREDIT");
		assertThat(((BigDecimal) item.get("amount"))).isEqualByComparingTo("55.00");
		assertThat(item.get("currency")).isEqualTo("USD");
		assertThat(item.get("createdAt")).isNotNull();
	}

	@Test
	void transferAppearsAsDebitForSourceAndCreditForDestinationSameTransaction() {
		UUID sourceId = createUsdCustomerAccount("History Owner Transfer Source");
		UUID destinationId = createUsdCustomerAccount("History Owner Transfer Destination");
		deposit(sourceId, "100.00");

		UUID transferTransactionId = transfer(sourceId, destinationId, "25.00").transactionId();

		List<Map<String, Object>> sourceHistory = historyContent(sourceId, null, null);
		Map<String, Object> sourceTransferItem = sourceHistory.stream()
				.filter(item -> item.get("transactionId").equals(transferTransactionId.toString()))
				.findFirst().orElseThrow();
		assertThat(sourceTransferItem.get("entryType")).isEqualTo("DEBIT");
		assertThat(((BigDecimal) sourceTransferItem.get("amount"))).isEqualByComparingTo("25.00");

		List<Map<String, Object>> destinationHistory = historyContent(destinationId, null, null);
		assertThat(destinationHistory).hasSize(1);
		Map<String, Object> destinationTransferItem = destinationHistory.get(0);
		assertThat(destinationTransferItem.get("transactionId")).isEqualTo(transferTransactionId.toString());
		assertThat(destinationTransferItem.get("entryType")).isEqualTo("CREDIT");
		assertThat(((BigDecimal) destinationTransferItem.get("amount"))).isEqualByComparingTo("25.00");
	}

	@Test
	void multipleDepositsAndTransfersAllAppearExactlyOnceForApplicableAccount() {
		UUID accountA = createUsdCustomerAccount("History Owner Multi A");
		UUID accountB = createUsdCustomerAccount("History Owner Multi B");

		deposit(accountA, "10.00");
		deposit(accountA, "20.00");
		transfer(accountA, accountB, "5.00");
		deposit(accountB, "1.00");

		List<Map<String, Object>> historyA = historyContent(accountA, 0, 100);
		List<Map<String, Object>> historyB = historyContent(accountB, 0, 100);

		// A: 2 deposit credits + 1 transfer debit = 3
		assertThat(historyA).hasSize(3);
		// B: 1 transfer credit + 1 deposit credit = 2
		assertThat(historyB).hasSize(2);
	}

	@Test
	void entriesBelongingOnlyToOtherAccountsDoNotAppear() {
		UUID accountA = createUsdCustomerAccount("History Owner Isolation A");
		UUID accountB = createUsdCustomerAccount("History Owner Isolation B");
		deposit(accountA, "10.00");
		deposit(accountB, "20.00");
		deposit(accountB, "30.00");

		List<Map<String, Object>> historyA = historyContent(accountA, null, null);

		assertThat(historyA).hasSize(1);
		assertThat(((BigDecimal) historyA.get(0).get("amount"))).isEqualByComparingTo("10.00");
	}

	@Test
	void historyOrderIsNewestFirst() throws InterruptedException {
		UUID accountId = createUsdCustomerAccount("History Owner Order");
		deposit(accountId, "1.00");
		Thread.sleep(5);
		deposit(accountId, "2.00");
		Thread.sleep(5);
		deposit(accountId, "3.00");

		List<Map<String, Object>> content = historyContent(accountId, null, null);

		assertThat(content).hasSize(3);
		assertThat(((BigDecimal) content.get(0).get("amount"))).isEqualByComparingTo("3.00");
		assertThat(((BigDecimal) content.get(1).get("amount"))).isEqualByComparingTo("2.00");
		assertThat(((BigDecimal) content.get(2).get("amount"))).isEqualByComparingTo("1.00");
	}

	@Test
	void systemAccountHistoryIsHiddenAs404() throws SQLException {
		UUID fundingAccountId = fetchUsdFundingAccountId();
		ResponseEntity<String> response = getHistoryRaw(fundingAccountId, null, null);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void nonexistentAccountHistoryReturns404() {
		ResponseEntity<String> response = getHistoryRaw(UUID.randomUUID(), null, null);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void malformedUuidForHistoryReturns400() {
		ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/accounts/not-a-uuid/transactions", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void readingHistoryCreatesNoLedgerRowsAndChangesNoBalance() throws SQLException {
		UUID accountId = createUsdCustomerAccount("History Owner Read Isolation");
		deposit(accountId, "20.00");
		long transactionsBefore = countLedgerTransactions();
		long entriesBefore = countLedgerEntries();

		historyContent(accountId, null, null);
		historyContent(accountId, null, null);

		assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore);
		assertThat(countLedgerEntries()).isEqualTo(entriesBefore);
		assertThat(getBalance(accountId).getBody().balance()).isEqualByComparingTo("20.00");
	}

	// ---- pagination ----

	@Test
	void defaultPaginationAppliesPageZeroSizeTwenty() {
		UUID accountId = createUsdCustomerAccount("History Owner Pagination Default");
		for (int i = 0; i < 5; i++) {
			deposit(accountId, "1.00");
		}

		Map<String, Object> page = getHistory(accountId, null, null);

		assertThat(page.get("page")).isEqualTo(0);
		assertThat(page.get("size")).isEqualTo(20);
		assertThat(((Number) page.get("totalElements")).longValue()).isEqualTo(5);
		assertThat(page.get("totalPages")).isEqualTo(1);
		assertThat((List<?>) page.get("content")).hasSize(5);
	}

	@Test
	void explicitPageAndSizeAreHonoredWithCorrectMetadataAndNoDuplicatesOrGaps() {
		UUID accountId = createUsdCustomerAccount("History Owner Pagination Explicit");
		for (int i = 1; i <= 25; i++) {
			deposit(accountId, "1.00");
		}

		Map<String, Object> page0 = getHistory(accountId, 0, 10);
		Map<String, Object> page1 = getHistory(accountId, 1, 10);
		Map<String, Object> page2 = getHistory(accountId, 2, 10);

		assertThat(((Number) page0.get("totalElements")).longValue()).isEqualTo(25);
		assertThat(page0.get("totalPages")).isEqualTo(3);
		assertThat((List<?>) page0.get("content")).hasSize(10);
		assertThat((List<?>) page1.get("content")).hasSize(10);
		assertThat((List<?>) page2.get("content")).hasSize(5);

		java.util.Set<Object> allTransactionIds = new java.util.HashSet<>();
		for (Map<String, Object> page : List.of(page0, page1, page2)) {
			for (Object item : (List<?>) page.get("content")) {
				Map<?, ?> m = (Map<?, ?>) item;
				allTransactionIds.add(m.get("transactionId"));
			}
		}
		assertThat(allTransactionIds).hasSize(25);
	}

	@Test
	void minimumAndMaximumAllowedSizesAreAccepted() {
		UUID accountId = createUsdCustomerAccount("History Owner Pagination Bounds");
		deposit(accountId, "1.00");

		assertThat(getHistoryRaw(accountId, 0, 1).getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(getHistoryRaw(accountId, 0, 100).getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void negativePageIsRejected() {
		UUID accountId = createUsdCustomerAccount("History Owner Negative Page");
		assertThat(getHistoryRaw(accountId, -1, 20).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void zeroSizeIsRejected() {
		UUID accountId = createUsdCustomerAccount("History Owner Zero Size");
		assertThat(getHistoryRaw(accountId, 0, 0).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void negativeSizeIsRejected() {
		UUID accountId = createUsdCustomerAccount("History Owner Negative Size");
		assertThat(getHistoryRaw(accountId, 0, -5).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void sizeExceedingMaximumIsRejected() {
		UUID accountId = createUsdCustomerAccount("History Owner Excess Size");
		assertThat(getHistoryRaw(accountId, 0, 101).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void malformedPaginationParametersAreRejected() {
		UUID accountId = createUsdCustomerAccount("History Owner Malformed Pagination");
		ResponseEntity<String> response = restTemplate.getForEntity(
				"/api/v1/accounts/" + accountId + "/transactions?page=abc&size=xyz", String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	// ------------------------------------------------------------------
	// cross-feature regression
	// ------------------------------------------------------------------

	@Test
	void depositAndTransferLedgerEntriesRemainBalancedAfterTask6Reads() throws SQLException {
		UUID sourceId = createUsdCustomerAccount("Regression Source");
		UUID destinationId = createUsdCustomerAccount("Regression Destination");
		deposit(sourceId, "100.00");
		UUID transferTxId = transfer(sourceId, destinationId, "40.00").transactionId();

		// Read endpoints exercised in between -- must not disturb anything.
		getBalance(sourceId);
		historyContent(sourceId, null, null);
		getBalance(destinationId);
		historyContent(destinationId, null, null);

		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT entry_type, amount FROM ledger_entry WHERE transaction_id = ?")) {
			statement.setObject(1, transferTxId);
			try (ResultSet resultSet = statement.executeQuery()) {
				BigDecimal debit = null;
				BigDecimal credit = null;
				while (resultSet.next()) {
					if ("DEBIT".equals(resultSet.getString("entry_type"))) {
						debit = resultSet.getBigDecimal("amount");
					}
					else {
						credit = resultSet.getBigDecimal("amount");
					}
				}
				assertThat(debit).isNotNull();
				assertThat(credit).isNotNull();
				assertThat(debit).isEqualByComparingTo(credit);
			}
		}
	}

	@Test
	void task6ReadsDoNotChangeExternalFundingBalance() throws SQLException {
		UUID fundingAccountId = fetchUsdFundingAccountId();
		BigDecimal before = fetchBalanceDirect(fundingAccountId);

		UUID accountId = createUsdCustomerAccount("Regression Funding Untouched");
		deposit(accountId, "10.00");
		BigDecimal afterDeposit = fetchBalanceDirect(fundingAccountId);

		getBalance(accountId);
		historyContent(accountId, null, null);

		assertThat(fetchBalanceDirect(fundingAccountId)).isEqualByComparingTo(afterDeposit);
		assertThat(afterDeposit).isEqualByComparingTo(before.add(new BigDecimal("10.00")));
	}

	@Test
	void ledgerImmutabilityTriggersStillRejectUpdateAndDelete() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Regression Immutability");
		UUID transactionId = deposit(accountId, "5.00").transactionId();

		try (Connection connection = dataSource.getConnection()) {
			try (PreparedStatement statement = connection.prepareStatement(
					"UPDATE ledger_transaction SET status = 'COMPLETED' WHERE id = ?")) {
				statement.setObject(1, transactionId);
				assertUpdateRejected(statement);
			}
			try (PreparedStatement statement = connection.prepareStatement(
					"DELETE FROM ledger_transaction WHERE id = ?")) {
				statement.setObject(1, transactionId);
				assertUpdateRejected(statement);
			}
		}
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

	private com.tarun.ledgerguard.account.dto.DepositResponse deposit(UUID accountId, String amount) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<com.tarun.ledgerguard.account.dto.DepositResponse> response = restTemplate.postForEntity(
				"/api/v1/accounts/" + accountId + "/deposits",
				new HttpEntity<>(Map.of("amount", amount, "currency", "USD"), headers),
				com.tarun.ledgerguard.account.dto.DepositResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody();
	}

	private com.tarun.ledgerguard.transfer.dto.TransferResponse transfer(UUID sourceId, UUID destinationId, String amount) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		Map<String, Object> body = Map.of(
				"sourceAccountId", sourceId.toString(),
				"destinationAccountId", destinationId.toString(),
				"amount", amount,
				"currency", "USD");
		ResponseEntity<com.tarun.ledgerguard.transfer.dto.TransferResponse> response = restTemplate.postForEntity(
				"/api/v1/transfers", new HttpEntity<>(body, headers), com.tarun.ledgerguard.transfer.dto.TransferResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody();
	}

	private ResponseEntity<AccountBalanceResponse> getBalance(UUID accountId) {
		return restTemplate.getForEntity("/api/v1/accounts/" + accountId + "/balance", AccountBalanceResponse.class);
	}

	private ResponseEntity<String> getBalanceRaw(UUID accountId) {
		return restTemplate.getForEntity("/api/v1/accounts/" + accountId + "/balance", String.class);
	}

	private Map<String, Object> getHistory(UUID accountId, Integer page, Integer size) {
		ResponseEntity<String> response = getHistoryRaw(accountId, page, size);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		try {
			return readJsonObject(response.getBody());
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private ResponseEntity<String> getHistoryRaw(UUID accountId, Integer page, Integer size) {
		return restTemplate.getForEntity(historyUrl(accountId, page, size), String.class);
	}

	@SuppressWarnings("unchecked")
	private List<Map<String, Object>> historyContent(UUID accountId, Integer page, Integer size) {
		return (List<Map<String, Object>>) (List<?>) getHistory(accountId, page, size).get("content");
	}

	private Map<String, Object> readJsonObject(String json) throws Exception {
		return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
		});
	}

	private String historyUrl(UUID accountId, Integer page, Integer size) {
		StringBuilder url = new StringBuilder("/api/v1/accounts/" + accountId + "/transactions");
		if (page != null || size != null) {
			url.append('?');
			if (page != null) {
				url.append("page=").append(page);
			}
			if (size != null) {
				if (page != null) {
					url.append('&');
				}
				url.append("size=").append(size);
			}
		}
		return url.toString();
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

	private BigDecimal fetchBalanceDirect(UUID accountId) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("SELECT balance FROM account WHERE id = ?")) {
			statement.setObject(1, accountId);
			try (ResultSet resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).isTrue();
				return resultSet.getBigDecimal("balance");
			}
		}
	}

	private long countLedgerTransactions() throws SQLException {
		return countRows("SELECT COUNT(*) FROM ledger_transaction");
	}

	private long countLedgerEntries() throws SQLException {
		return countRows("SELECT COUNT(*) FROM ledger_entry");
	}

	private long countRows(String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(sql);
				ResultSet resultSet = statement.executeQuery()) {
			resultSet.next();
			return resultSet.getLong(1);
		}
	}

	private void assertUpdateRejected(PreparedStatement statement) throws SQLException {
		try {
			statement.executeUpdate();
			throw new AssertionError("Expected immutability trigger to reject the statement");
		}
		catch (SQLException expected) {
			assertThat(expected.getMessage()).contains("immutable");
		}
	}

}
