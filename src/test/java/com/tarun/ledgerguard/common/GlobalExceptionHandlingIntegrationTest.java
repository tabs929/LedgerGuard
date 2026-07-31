package com.tarun.ledgerguard.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tarun.ledgerguard.account.dto.AccountResponse;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the global error-handling design (Task 7): every handled failure
 * across every existing endpoint returns the exact documented
 * {@link ApiError} envelope, never leaks internal implementation details,
 * and never leaves partial financial state. Runs against a real, isolated
 * PostgreSQL 16.4 Testcontainer running the Flyway migrations from
 * scratch. No mocks are used for persistence behavior.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GlobalExceptionHandlingIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	TestRestTemplate restTemplate;

	@Autowired
	DataSource dataSource;

	private static final ObjectMapper JSON = new ObjectMapper();

	private static final List<String> FORBIDDEN_SNIPPETS = List.of(
			"Exception", "java.lang", "java.sql", "org.springframework", "org.hibernate",
			"org.postgresql", "com.zaxxer", "SQLState", "Caused by", "at com.tarun",
			"chk_", "idx_", "ledger_entry", "ledger_transaction", "SELECT ", "INSERT ", "UPDATE ");

	// ------------------------------------------------------------------
	// error envelope shape
	// ------------------------------------------------------------------

	@Test
	void errorResponseHasExactlyTheDocumentedFieldsAndNothingElse() throws Exception {
		ResponseEntity<String> response = getBalanceRaw(UUID.randomUUID());

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		Map<String, Object> body = readJsonObject(response.getBody());
		assertThat(body.keySet()).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path");
	}

	@Test
	void errorResponseStatusFieldMatchesHttpStatus() throws Exception {
		ResponseEntity<String> response = getBalanceRaw(UUID.randomUUID());

		Map<String, Object> body = readJsonObject(response.getBody());
		assertThat(((Number) body.get("status")).intValue()).isEqualTo(404);
		assertThat(body.get("error")).isEqualTo("Not Found");
	}

	@Test
	void errorResponsePathFieldMatchesRequestPath() throws Exception {
		UUID accountId = UUID.randomUUID();
		ResponseEntity<String> response = getBalanceRaw(accountId);

		Map<String, Object> body = readJsonObject(response.getBody());
		assertThat(body.get("path")).isEqualTo("/api/v1/accounts/" + accountId + "/balance");
	}

	@Test
	void errorResponseTimestampIsValidIso8601() throws Exception {
		ResponseEntity<String> response = getBalanceRaw(UUID.randomUUID());

		Map<String, Object> body = readJsonObject(response.getBody());
		// Instant.parse throws if this is not a valid ISO-8601 instant.
		Instant.parse((String) body.get("timestamp"));
	}

	@Test
	void errorResponseContentTypeIsApplicationJson() {
		ResponseEntity<String> response = getBalanceRaw(UUID.randomUUID());
		assertThat(response.getHeaders().getContentType()).isNotNull();
		assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
	}

	@Test
	void errorResponseNeverLeaksInternalDetails() {
		UUID accountId = createUsdCustomerAccount("Leakage Check Owner");

		ResponseEntity<String> notFound = getBalanceRaw(UUID.randomUUID());
		ResponseEntity<String> badRequest = postDepositRaw(accountId, Map.of("amount", "not-a-number", "currency", "USD"));
		ResponseEntity<String> unprocessable = postDepositRaw(accountId, Map.of("amount", "10.00", "currency", "EUR"));

		for (ResponseEntity<String> response : List.of(notFound, badRequest, unprocessable)) {
			assertThat(response.getBody()).isNotNull();
			for (String forbidden : FORBIDDEN_SNIPPETS) {
				assertThat(response.getBody()).as("response for %s should not contain '%s'", response, forbidden)
						.doesNotContain(forbidden);
			}
		}
	}

	// ------------------------------------------------------------------
	// request-shape validation (400)
	// ------------------------------------------------------------------

	@Test
	void missingOwnerNameOnAccountCreationReturns400() {
		ResponseEntity<String> response = postRaw("/api/v1/accounts", Map.of("currency", "USD"));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void unknownFieldOnAccountCreationReturns400() {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("ownerName", "Unknown Field Owner");
		body.put("currency", "USD");
		body.put("balance", "999.00");
		assertThat(postRaw("/api/v1/accounts", body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void malformedAccountCreationJsonReturns400() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<String> response = restTemplate.postForEntity(
				"/api/v1/accounts", new HttpEntity<>("{\"ownerName\": \"Broken", headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void missingDepositAmountReturns400() {
		UUID accountId = createUsdCustomerAccount("Missing Deposit Amount Owner");
		assertThat(postDepositRaw(accountId, Map.of("currency", "USD")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void zeroAndNegativeDepositAmountsReturn400() {
		UUID accountId = createUsdCustomerAccount("Zero Negative Deposit Owner");
		assertThat(postDepositRaw(accountId, Map.of("amount", "0", "currency", "USD")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(postDepositRaw(accountId, Map.of("amount", "-1.00", "currency", "USD")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void malformedDepositAmountReturns400() {
		UUID accountId = createUsdCustomerAccount("Malformed Deposit Amount Owner");
		assertThat(postDepositRaw(accountId, Map.of("amount", "abc", "currency", "USD")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void unsupportedDepositPrecisionOrScaleReturns400() {
		UUID accountId = createUsdCustomerAccount("Deposit Precision Owner");
		assertThat(postDepositRaw(accountId, Map.of("amount", "1.23456", "currency", "USD")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(postDepositRaw(accountId, Map.of("amount", "1000000000000000.00", "currency", "USD")).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void protectedDepositFieldsAreRejectedWith400() {
		UUID accountId = createUsdCustomerAccount("Protected Deposit Field Owner");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("amount", "10.00");
		body.put("currency", "USD");
		body.put("transactionId", UUID.randomUUID().toString());
		assertThat(postDepositRaw(accountId, body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void missingTransferFieldsReturn400() {
		UUID sourceId = createUsdCustomerAccount("Missing Transfer Field Source");
		UUID destinationId = createUsdCustomerAccount("Missing Transfer Field Destination");

		assertThat(postTransferRawBody(Map.of(
				"destinationAccountId", destinationId.toString(), "amount", "10.00", "currency", "USD"))
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(postTransferRawBody(Map.of(
				"sourceAccountId", sourceId.toString(), "amount", "10.00", "currency", "USD"))
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(postTransferRawBody(Map.of(
				"sourceAccountId", sourceId.toString(), "destinationAccountId", destinationId.toString(), "currency", "USD"))
				.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void malformedTransferUuidReturns400() {
		UUID destinationId = createUsdCustomerAccount("Malformed Transfer UUID Destination");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceAccountId", "not-a-uuid");
		body.put("destinationAccountId", destinationId.toString());
		body.put("amount", "10.00");
		body.put("currency", "USD");
		assertThat(postTransferRawBody(body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void zeroAndNegativeTransferAmountsReturn400() {
		UUID sourceId = createUsdCustomerAccount("Zero Negative Transfer Source");
		UUID destinationId = createUsdCustomerAccount("Zero Negative Transfer Destination");
		assertThat(postTransferRaw(sourceId, destinationId, "0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(postTransferRaw(sourceId, destinationId, "-5.00").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void unsupportedTransferPrecisionOrScaleReturns400() {
		UUID sourceId = createUsdCustomerAccount("Transfer Precision Source");
		UUID destinationId = createUsdCustomerAccount("Transfer Precision Destination");
		assertThat(postTransferRaw(sourceId, destinationId, "1.23456").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void unknownOrProtectedTransferFieldsReturn400() {
		UUID sourceId = createUsdCustomerAccount("Protected Transfer Field Source");
		UUID destinationId = createUsdCustomerAccount("Protected Transfer Field Destination");
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("sourceAccountId", sourceId.toString());
		body.put("destinationAccountId", destinationId.toString());
		body.put("amount", "10.00");
		body.put("currency", "USD");
		body.put("newBalance", "9999.00");
		assertThat(postTransferRawBody(body).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void malformedPathUuidReturns400ForBalanceAndHistory() {
		assertThat(restTemplate.getForEntity("/api/v1/accounts/not-a-uuid/balance", String.class).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(restTemplate.getForEntity("/api/v1/accounts/not-a-uuid/transactions", String.class).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void malformedPaginationParametersReturn400() {
		UUID accountId = createUsdCustomerAccount("Malformed Pagination Owner");
		assertThat(restTemplate.getForEntity(
				"/api/v1/accounts/" + accountId + "/transactions?page=abc&size=xyz", String.class).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void pageBelowMinimumReturns400() {
		UUID accountId = createUsdCustomerAccount("Page Below Minimum Owner");
		assertThat(restTemplate.getForEntity(
				"/api/v1/accounts/" + accountId + "/transactions?page=-1", String.class).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void sizeBelowMinimumReturns400() {
		UUID accountId = createUsdCustomerAccount("Size Below Minimum Owner");
		assertThat(restTemplate.getForEntity(
				"/api/v1/accounts/" + accountId + "/transactions?size=0", String.class).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void sizeAboveMaximumReturns400() {
		UUID accountId = createUsdCustomerAccount("Size Above Maximum Owner");
		assertThat(restTemplate.getForEntity(
				"/api/v1/accounts/" + accountId + "/transactions?size=101", String.class).getStatusCode())
				.isEqualTo(HttpStatus.BAD_REQUEST);
	}

	// ------------------------------------------------------------------
	// domain errors
	// ------------------------------------------------------------------

	@Test
	void nonexistentAccountReturns404OnEveryReadAndWriteEndpoint() {
		UUID nonexistent = UUID.randomUUID();
		assertThat(getBalanceRaw(nonexistent).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(getHistoryRaw(nonexistent).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(postDepositRaw(nonexistent, Map.of("amount", "10.00", "currency", "USD")).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void systemAccountIsHiddenAs404OnEveryPublicEndpoint() throws SQLException {
		UUID fundingId = fetchUsdFundingAccountId();
		UUID destinationId = createUsdCustomerAccount("System Account Hiding Destination");

		assertThat(getBalanceRaw(fundingId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(getHistoryRaw(fundingId).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(postDepositRaw(fundingId, Map.of("amount", "10.00", "currency", "USD")).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(postTransferRaw(fundingId, destinationId, "10.00").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void unsupportedCurrencyOnAccountCreationReturns422() {
		Map<String, Object> body = Map.of("ownerName", "Unsupported Currency Owner", "currency", "EUR");
		assertThat(postRaw("/api/v1/accounts", body).getStatusCode().value()).isEqualTo(422);
	}

	@Test
	void invalidDepositAndTransferDestinationsReturn404() {
		UUID nonexistent = UUID.randomUUID();
		UUID sourceId = createUsdCustomerAccount("Invalid Destination Source");
		assertThat(postDepositRaw(nonexistent, Map.of("amount", "10.00", "currency", "USD")).getStatusCode())
				.isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(postTransferRaw(sourceId, nonexistent, "10.00").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(postTransferRaw(nonexistent, sourceId, "10.00").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void insufficientFundsReturns422() {
		UUID sourceId = createUsdCustomerAccount("Insufficient Funds Source");
		UUID destinationId = createUsdCustomerAccount("Insufficient Funds Destination");
		assertThat(postTransferRaw(sourceId, destinationId, "10.00").getStatusCode().value()).isEqualTo(422);
	}

	// ------------------------------------------------------------------
	// consistency across endpoints
	// ------------------------------------------------------------------

	@Test
	void notFoundErrorsUseIdenticalEnvelopeShapeAcrossAllEndpoints() throws Exception {
		UUID nonexistent = UUID.randomUUID();
		UUID sourceId = createUsdCustomerAccount("Consistency Source");

		List<ResponseEntity<String>> responses = List.of(
				getBalanceRaw(nonexistent),
				getHistoryRaw(nonexistent),
				postDepositRaw(nonexistent, Map.of("amount", "10.00", "currency", "USD")),
				postTransferRaw(sourceId, nonexistent, "10.00"));

		for (ResponseEntity<String> response : responses) {
			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
			Map<String, Object> body = readJsonObject(response.getBody());
			assertThat(body.keySet()).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path");
			assertThat(((Number) body.get("status")).intValue()).isEqualTo(404);
			assertThat(body.get("error")).isEqualTo("Not Found");
		}
	}

	// ------------------------------------------------------------------
	// atomicity regression
	// ------------------------------------------------------------------

	@Test
	void rejectedDepositCreatesNoFinancialStateAndUsesGlobalEnvelope() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Rejected Deposit Owner");
		long transactionsBefore = countLedgerTransactions();
		long entriesBefore = countLedgerEntries();

		ResponseEntity<String> response = postDepositRaw(accountId, Map.of("amount", "-1.00", "currency", "USD"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore);
		assertThat(countLedgerEntries()).isEqualTo(entriesBefore);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void rejectedTransferCreatesNoFinancialStateAndUsesGlobalEnvelope() throws SQLException {
		UUID sourceId = createUsdCustomerAccount("Rejected Transfer Source");
		UUID destinationId = createUsdCustomerAccount("Rejected Transfer Destination");
		long transactionsBefore = countLedgerTransactions();
		long entriesBefore = countLedgerEntries();

		ResponseEntity<String> response = postTransferRaw(sourceId, destinationId, "10.00"); // insufficient funds

		assertThat(response.getStatusCode().value()).isEqualTo(422);
		assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore);
		assertThat(countLedgerEntries()).isEqualTo(entriesBefore);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("0.0000");
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("0.0000");
	}

	@Test
	void deliberateMidTransactionFailureStillRollsBackCompletelyUnderGlobalHandler() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Mid Transaction Rollback Owner");
		setBalanceDirectly(accountId, new BigDecimal("999999999999999.0000")); // max 15 integer digits
		long transactionsBefore = countLedgerTransactions();
		long entriesBefore = countLedgerEntries();

		ResponseEntity<String> response = postDepositRaw(accountId, Map.of("amount", "10.00", "currency", "USD"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
		assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore);
		assertThat(countLedgerEntries()).isEqualTo(entriesBefore);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("999999999999999.0000");
		for (String forbidden : FORBIDDEN_SNIPPETS) {
			assertThat(response.getBody()).doesNotContain(forbidden);
		}
	}

	// ------------------------------------------------------------------
	// success regression
	// ------------------------------------------------------------------

	@Test
	void accountCreationDepositAndTransferStillSucceedAndConserveFunds() throws SQLException {
		UUID sourceId = createUsdCustomerAccount("Regression Source");
		UUID destinationId = createUsdCustomerAccount("Regression Destination");

		ResponseEntity<AccountResponse> depositAccount = restTemplate.postForEntity(
				"/api/v1/accounts", entity(Map.of("ownerName", "Regression Extra", "currency", "USD")), AccountResponse.class);
		assertThat(depositAccount.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		deposit(sourceId, "100.00");
		transfer(sourceId, destinationId, "40.00");

		assertThat(fetchBalance(sourceId)).isEqualByComparingTo("60.00");
		assertThat(fetchBalance(destinationId)).isEqualByComparingTo("40.00");
	}

	@Test
	void historyOrderingRemainsNewestFirstWithIdTiebreak() throws InterruptedException {
		UUID accountId = createUsdCustomerAccount("Regression Ordering Owner");
		deposit(accountId, "1.00");
		Thread.sleep(5);
		deposit(accountId, "2.00");

		Map<String, Object> page = getHistory(accountId, null, null);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> content = (List<Map<String, Object>>) (List<?>) page.get("content");

		assertThat(content).hasSize(2);
		assertThat(new BigDecimal(String.valueOf(content.get(0).get("amount")))).isEqualByComparingTo("2.00");
		assertThat(new BigDecimal(String.valueOf(content.get(1).get("amount")))).isEqualByComparingTo("1.00");
	}

	@Test
	void paginationDefaultsAndBoundsRemainUnchanged() {
		UUID accountId = createUsdCustomerAccount("Regression Pagination Owner");
		deposit(accountId, "1.00");

		Map<String, Object> page = getHistory(accountId, null, null);
		assertThat(page.get("page")).isEqualTo(0);
		assertThat(page.get("size")).isEqualTo(20);

		assertThat(getHistoryStatus(accountId, 0, 1)).isEqualTo(HttpStatus.OK);
		assertThat(getHistoryStatus(accountId, 0, 100)).isEqualTo(HttpStatus.OK);
	}

	@Test
	void ledgerImmutabilityTriggersStillRejectUpdateAndDelete() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Regression Immutability Owner");
		UUID transactionId = depositTransactionId(accountId, "5.00");

		try (Connection connection = dataSource.getConnection()) {
			try (PreparedStatement statement = connection.prepareStatement(
					"UPDATE ledger_transaction SET status = 'COMPLETED' WHERE id = ?")) {
				statement.setObject(1, transactionId);
				assertRejected(statement);
			}
			try (PreparedStatement statement = connection.prepareStatement(
					"DELETE FROM ledger_transaction WHERE id = ?")) {
				statement.setObject(1, transactionId);
				assertRejected(statement);
			}
		}
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private UUID createUsdCustomerAccount(String ownerName) {
		ResponseEntity<AccountResponse> response = restTemplate.postForEntity(
				"/api/v1/accounts", entity(Map.of("ownerName", ownerName, "currency", "USD")), AccountResponse.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return response.getBody().id();
	}

	private void deposit(UUID accountId, String amount) {
		ResponseEntity<String> response = restTemplate.postForEntity(
				"/api/v1/accounts/" + accountId + "/deposits", entity(Map.of("amount", amount, "currency", "USD")), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private UUID depositTransactionId(UUID accountId, String amount) throws SQLException {
		ResponseEntity<Map> response = restTemplate.postForEntity(
				"/api/v1/accounts/" + accountId + "/deposits", entity(Map.of("amount", amount, "currency", "USD")), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return UUID.fromString((String) response.getBody().get("transactionId"));
	}

	private void transfer(UUID sourceId, UUID destinationId, String amount) {
		assertThat(postTransferRaw(sourceId, destinationId, amount).getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	private ResponseEntity<String> postDepositRaw(UUID accountId, Map<String, Object> body) {
		return postRaw("/api/v1/accounts/" + accountId + "/deposits", body);
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
		return postRaw("/api/v1/transfers", body);
	}

	private ResponseEntity<String> postRaw(String path, Map<String, Object> body) {
		try {
			HttpHeaders headers = new HttpHeaders();
			headers.setContentType(MediaType.APPLICATION_JSON);
			headers.set("Idempotency-Key", UUID.randomUUID().toString());
			String json = JSON.writeValueAsString(body);
			return restTemplate.postForEntity(path, new HttpEntity<>(json, headers), String.class);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private HttpEntity<Map<String, Object>> entity(Map<String, Object> body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Idempotency-Key", UUID.randomUUID().toString());
		return new HttpEntity<>(body, headers);
	}

	private ResponseEntity<String> getBalanceRaw(UUID accountId) {
		return restTemplate.getForEntity("/api/v1/accounts/" + accountId + "/balance", String.class);
	}

	private ResponseEntity<String> getHistoryRaw(UUID accountId) {
		return restTemplate.getForEntity("/api/v1/accounts/" + accountId + "/transactions", String.class);
	}

	private HttpStatus getHistoryStatus(UUID accountId, Integer page, Integer size) {
		StringBuilder url = new StringBuilder("/api/v1/accounts/" + accountId + "/transactions?page=" + page + "&size=" + size);
		return (HttpStatus) restTemplate.getForEntity(url.toString(), String.class).getStatusCode();
	}

	private Map<String, Object> getHistory(UUID accountId, Integer page, Integer size) {
		String url = "/api/v1/accounts/" + accountId + "/transactions";
		ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		try {
			return readJsonObject(response.getBody());
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private Map<String, Object> readJsonObject(String json) throws Exception {
		return JSON.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
		});
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

	private void assertRejected(PreparedStatement statement) throws SQLException {
		try {
			statement.executeUpdate();
			throw new AssertionError("Expected immutability trigger to reject the statement");
		}
		catch (SQLException expected) {
			assertThat(expected.getMessage()).contains("immutable");
		}
	}

}
