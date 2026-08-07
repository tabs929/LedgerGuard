package com.tarun.ledgerguard.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
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
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 17: stateless JWT authentication and ownership-based authorization,
 * exercised end-to-end against a real, isolated PostgreSQL 16.4
 * Testcontainer. Covers: missing/malformed/wrong-signature/wrong-issuer/
 * wrong-audience/expired tokens (401); role-based capability denial (403);
 * account-specific ownership denial (404, per the project's existing
 * SYSTEM-account precedent); the explicit idempotency-replay-authorization
 * ordering requirement; and that account creation always uses the
 * authenticated subject.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthenticationAndAuthorizationIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	TestRestTemplate restTemplate;

	@Autowired
	DataSource dataSource;

	// ------------------------------------------------------------------
	// authentication endpoint
	// ------------------------------------------------------------------

	@Test
	void validCredentialsIssueAToken() {
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/token",
				Map.of("username", TestAuthSupport.CUSTOMER_A_USERNAME, "password", TestAuthSupport.CUSTOMER_A_PASSWORD),
				Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(response.getBody().get("accessToken")).isNotNull();
		assertThat(response.getBody().get("tokenType")).isEqualTo("Bearer");
		assertThat(((Number) response.getBody().get("expiresInSeconds")).longValue()).isGreaterThan(0);
	}

	@Test
	void unknownUsernameReturns401WithGenericMessage() {
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/token",
				Map.of("username", "no-such-user-" + UUID.randomUUID(), "password", "irrelevant"), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody().get("message")).isEqualTo("Invalid username or password.");
	}

	@Test
	void wrongPasswordReturns401WithTheIdenticalGenericMessage() {
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/token",
				Map.of("username", TestAuthSupport.CUSTOMER_A_USERNAME, "password", "definitely-wrong"), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		// Identical message/status to the unknown-username case above -- a
		// client can never distinguish which one occurred.
		assertThat(response.getBody().get("message")).isEqualTo("Invalid username or password.");
	}

	@Test
	void tokenRequestCannotChooseARole() {
		// TokenRequest has no role field at all -- an extra JSON property is
		// rejected outright (spring.jackson.deserialization.fail-on-unknown-properties).
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		Map<String, Object> body = Map.of("username", TestAuthSupport.CUSTOMER_A_USERNAME,
				"password", TestAuthSupport.CUSTOMER_A_PASSWORD, "role", "OPERATIONS");
		ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/auth/token",
				new HttpEntity<>(body, headers), String.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	// ------------------------------------------------------------------
	// missing / malformed / invalid bearer token -> 401
	// ------------------------------------------------------------------

	@Test
	void missingBearerTokenOnProtectedEndpointReturns401() {
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/accounts",
				Map.of("ownerName", "No Token Owner", "currency", "USD"), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void malformedBearerTokenReturns401() {
		ResponseEntity<Map> response = postAccountWithRawToken("this-is-not-a-jwt");
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void tokenSignedWithAWrongKeyReturns401() {
		String wrongKey = java.util.Base64.getEncoder().encodeToString(new byte[32]);
		String token = TestAuthSupport.craftToken(wrongKey, TestAuthSupport.TEST_ISSUER, TestAuthSupport.TEST_AUDIENCE,
				"forged-subject", "CUSTOMER", Instant.now(), Instant.now().plusSeconds(300));
		assertThat(postAccountWithRawToken(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void tokenWithWrongIssuerReturns401() {
		String token = TestAuthSupport.craftToken(TestAuthSupport.TEST_SIGNING_KEY_BASE64, "some-other-issuer",
				TestAuthSupport.TEST_AUDIENCE, TestAuthSupport.CUSTOMER_A_USERNAME, "CUSTOMER", Instant.now(),
				Instant.now().plusSeconds(300));
		assertThat(postAccountWithRawToken(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void tokenWithWrongAudienceReturns401() {
		String token = TestAuthSupport.craftToken(TestAuthSupport.TEST_SIGNING_KEY_BASE64, TestAuthSupport.TEST_ISSUER,
				"some-other-audience", TestAuthSupport.CUSTOMER_A_USERNAME, "CUSTOMER", Instant.now(),
				Instant.now().plusSeconds(300));
		assertThat(postAccountWithRawToken(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void expiredTokenReturns401() {
		String token = TestAuthSupport.craftToken(TestAuthSupport.TEST_SIGNING_KEY_BASE64, TestAuthSupport.TEST_ISSUER,
				TestAuthSupport.TEST_AUDIENCE, TestAuthSupport.CUSTOMER_A_USERNAME, "CUSTOMER",
				Instant.now().minusSeconds(3600), Instant.now().minusSeconds(1800));
		assertThat(postAccountWithRawToken(token).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	// ------------------------------------------------------------------
	// actuator: health is public, everything else is OPERATIONS-only
	// ------------------------------------------------------------------

	@Test
	void actuatorHealthIsPublic() {
		ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	@Test
	void unauthenticatedRequestToNonHealthActuatorPathReturns401() {
		// Not exposed as a real actuator endpoint in this application (only
		// "health" is), but the SecurityFilterChain's /actuator/** rule
		// still must reject an unauthenticated request to it BEFORE any
		// question of whether it exists is ever reached -- same "protected
		// namespace, not route existence" precedent as /api/v1/**.
		ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/env", Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void customerCannotAccessNonHealthActuatorPath() {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(TestAuthSupport.customerAToken(restTemplate));
		ResponseEntity<Map> response = restTemplate.exchange("/actuator/env", HttpMethod.GET,
				new HttpEntity<>(headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void operationsPassesTheActuatorAuthorizationGateForANonHealthPath() {
		// "/actuator/env" is not registered as a real endpoint in this
		// application (nothing exposes it), so once OPERATIONS clears the
		// SecurityFilterChain's role gate, the request still falls through
		// to a plain 404 -- never 401/403. This is what proves the gate
		// itself, not mere non-existence, produced the 401/403 above.
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(TestAuthSupport.operationsToken(restTemplate));
		ResponseEntity<Map> response = restTemplate.exchange("/actuator/env", HttpMethod.GET,
				new HttpEntity<>(headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void unauthenticated401ResponseUsesTheSharedApiErrorEnvelope() {
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/accounts",
				Map.of("ownerName", "Envelope Check", "currency", "USD"), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(response.getBody().keySet()).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path");
		assertThat(response.getBody().get("status")).isEqualTo(401);
	}

	private ResponseEntity<Map> postAccountWithRawToken(String rawToken) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(rawToken);
		return restTemplate.postForEntity("/api/v1/accounts",
				new HttpEntity<>(Map.of("ownerName", "Bad Token Owner", "currency", "USD"), headers), Map.class);
	}

	// ------------------------------------------------------------------
	// role-based capability denial -> 403
	// ------------------------------------------------------------------

	@Test
	void operationsCannotCreateAnAccount() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(TestAuthSupport.operationsToken(restTemplate));
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/accounts",
				new HttpEntity<>(Map.of("ownerName", "Operations Attempt", "currency", "USD"), headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void operationsCannotDeposit() {
		UUID accountId = createAccount(TestAuthSupport.customerAToken(restTemplate));
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(TestAuthSupport.operationsToken(restTemplate));
		headers.set("Idempotency-Key", UUID.randomUUID().toString());
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/accounts/" + accountId + "/deposits",
				new HttpEntity<>(Map.of("amount", "10.00", "currency", "USD"), headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void operationsCannotTransfer() {
		String tokenA = TestAuthSupport.customerAToken(restTemplate);
		UUID sourceId = createFundedAccount(tokenA, "50.00");
		UUID destinationId = createAccount(tokenA);
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(TestAuthSupport.operationsToken(restTemplate));
		headers.set("Idempotency-Key", UUID.randomUUID().toString());
		Map<String, Object> body = Map.of("sourceAccountId", sourceId.toString(),
				"destinationAccountId", destinationId.toString(), "amount", "10.00", "currency", "USD");
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/transfers",
				new HttpEntity<>(body, headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void customerCannotImportSettlementFiles() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		headers.setBearerAuth(TestAuthSupport.customerAToken(restTemplate));
		org.springframework.util.MultiValueMap<String, Object> form = new org.springframework.util.LinkedMultiValueMap<>();
		form.add("source", "acme-bank");
		form.add("file", new org.springframework.core.io.ByteArrayResource(
				("external_reference,transaction_id,amount,currency,settled_at\n").getBytes()) {
			@Override
			public String getFilename() {
				return "settlement.csv";
			}
		});
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/settlement-imports",
				new HttpEntity<>(form, headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void customerCannotStartReconciliation() {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(TestAuthSupport.customerAToken(restTemplate));
		ResponseEntity<Map> response = restTemplate.postForEntity(
				"/api/v1/settlement-imports/" + UUID.randomUUID() + "/reconciliation",
				new HttpEntity<>(headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void forbidden403ResponseUsesTheSharedApiErrorEnvelope() {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(TestAuthSupport.operationsToken(restTemplate));
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/accounts",
				new HttpEntity<>(Map.of("ownerName", "Envelope Check", "currency", "USD"), headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(response.getBody().keySet()).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path");
		assertThat(response.getBody().get("status")).isEqualTo(403);
	}

	// ------------------------------------------------------------------
	// account creation always uses the authenticated subject
	// ------------------------------------------------------------------

	@Test
	void createdAccountsCustomerSubjectMatchesTheAuthenticatedPrincipalNotAnythingInTheRequest() throws SQLException {
		UUID accountId = createAccount(TestAuthSupport.customerAToken(restTemplate));
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT customer_subject FROM account WHERE id = ?")) {
			statement.setObject(1, accountId);
			try (ResultSet resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).isTrue();
				assertThat(resultSet.getString("customer_subject")).isEqualTo(TestAuthSupport.CUSTOMER_A_USERNAME);
			}
		}
	}

	// ------------------------------------------------------------------
	// ownership: a CUSTOMER cannot read/deposit-into/transfer-from
	// another customer's account -- 404, per the SYSTEM-account precedent
	// ------------------------------------------------------------------

	@Test
	void customerCannotReadAnotherCustomersBalance() {
		UUID accountId = createAccount(TestAuthSupport.customerAToken(restTemplate));
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(TestAuthSupport.customerBToken(restTemplate));
		ResponseEntity<Map> response = restTemplate.exchange("/api/v1/accounts/" + accountId + "/balance",
				HttpMethod.GET, new HttpEntity<>(headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void customerCannotReadAnotherCustomersTransactionHistory() {
		UUID accountId = createAccount(TestAuthSupport.customerAToken(restTemplate));
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(TestAuthSupport.customerBToken(restTemplate));
		ResponseEntity<Map> response = restTemplate.exchange("/api/v1/accounts/" + accountId + "/transactions",
				HttpMethod.GET, new HttpEntity<>(headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void customerCannotDepositIntoAnotherCustomersAccount() throws SQLException {
		UUID accountId = createAccount(TestAuthSupport.customerAToken(restTemplate));
		BigDecimal before = fetchBalance(accountId);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(TestAuthSupport.customerBToken(restTemplate));
		headers.set("Idempotency-Key", UUID.randomUUID().toString());
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/accounts/" + accountId + "/deposits",
				new HttpEntity<>(Map.of("amount", "10.00", "currency", "USD"), headers), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(fetchBalance(accountId)).isEqualByComparingTo(before);
	}

	@Test
	void customerCannotTransferFromAnotherCustomersAccount() throws SQLException {
		String tokenA = TestAuthSupport.customerAToken(restTemplate);
		UUID sourceId = createFundedAccount(tokenA, "50.00");
		UUID destinationId = createAccount(TestAuthSupport.customerBToken(restTemplate));
		BigDecimal sourceBefore = fetchBalance(sourceId);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(TestAuthSupport.customerBToken(restTemplate));
		headers.set("Idempotency-Key", UUID.randomUUID().toString());
		Map<String, Object> body = Map.of("sourceAccountId", sourceId.toString(),
				"destinationAccountId", destinationId.toString(), "amount", "10.00", "currency", "USD");
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/transfers",
				new HttpEntity<>(body, headers), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(fetchBalance(sourceId)).isEqualByComparingTo(sourceBefore);
	}

	@Test
	void customerCanTransferToAnAccountTheyDoNotOwnWithoutSeeingItsBalance() {
		String tokenA = TestAuthSupport.customerAToken(restTemplate);
		String tokenB = TestAuthSupport.customerBToken(restTemplate);
		UUID sourceId = createFundedAccount(tokenA, "50.00");
		UUID destinationId = createAccount(tokenB);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(tokenA);
		headers.set("Idempotency-Key", UUID.randomUUID().toString());
		Map<String, Object> body = Map.of("sourceAccountId", sourceId.toString(),
				"destinationAccountId", destinationId.toString(), "amount", "10.00", "currency", "USD");
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/transfers",
				new HttpEntity<>(body, headers), Map.class);

		// A allowed to transfer to B's account, but the response never
		// carries B's balance -- TransferResponse simply has no such field.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).doesNotContainKeys("destinationBalance", "newBalance");
	}

	@Test
	void operationsCanReadAnyCustomersBalanceAndHistory() {
		UUID accountId = createAccount(TestAuthSupport.customerAToken(restTemplate));
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(TestAuthSupport.operationsToken(restTemplate));

		ResponseEntity<Map> balance = restTemplate.exchange("/api/v1/accounts/" + accountId + "/balance",
				HttpMethod.GET, new HttpEntity<>(headers), Map.class);
		assertThat(balance.getStatusCode()).isEqualTo(HttpStatus.OK);

		ResponseEntity<Map> history = restTemplate.exchange("/api/v1/accounts/" + accountId + "/transactions",
				HttpMethod.GET, new HttpEntity<>(headers), Map.class);
		assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
	}

	// ------------------------------------------------------------------
	// idempotency: authorization must precede any replay, and a
	// cross-principal attempt must never mutate financial state
	// ------------------------------------------------------------------

	@Test
	void aDifferentCustomerCannotRetrieveAnotherCustomersStoredDepositResponse() throws SQLException {
		String tokenA = TestAuthSupport.customerAToken(restTemplate);
		String tokenB = TestAuthSupport.customerBToken(restTemplate);
		UUID accountA = createAccount(tokenA);
		String sharedKey = UUID.randomUUID().toString();

		ResponseEntity<Map> original = postDeposit(accountA, "10.00", sharedKey, tokenA);
		assertThat(original.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		long idempotencyRowsBefore = countRows("idempotency_key");
		long ledgerTransactionsBefore = countRows("ledger_transaction");
		long outboxEventsBefore = countRows("outbox_event");
		BigDecimal accountABalanceBefore = fetchBalance(accountA);

		// B reuses the identical key string, targeting A's account (which B
		// does not own). Authorization (ownership) must be checked BEFORE
		// any idempotency lookup, so B never even reaches, let alone
		// replays, A's stored response.
		ResponseEntity<Map> replayAttempt = postDeposit(accountA, "10.00", sharedKey, tokenB);

		assertThat(replayAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(replayAttempt.getBody().toString()).doesNotContain(String.valueOf(original.getBody().get("transactionId")));
		assertThat(countRows("idempotency_key")).isEqualTo(idempotencyRowsBefore);
		assertThat(countRows("ledger_transaction")).isEqualTo(ledgerTransactionsBefore);
		assertThat(countRows("outbox_event")).isEqualTo(outboxEventsBefore);
		assertThat(fetchBalance(accountA)).isEqualByComparingTo(accountABalanceBefore);
	}

	@Test
	void aDifferentCustomerCannotRetrieveAnotherCustomersStoredTransferResponse() throws SQLException {
		String tokenA = TestAuthSupport.customerAToken(restTemplate);
		String tokenB = TestAuthSupport.customerBToken(restTemplate);
		UUID sourceA = createFundedAccount(tokenA, "50.00");
		UUID destinationA = createAccount(tokenA);
		String sharedKey = UUID.randomUUID().toString();

		ResponseEntity<Map> original = postTransfer(sourceA, destinationA, "10.00", sharedKey, tokenA);
		assertThat(original.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		long idempotencyRowsBefore = countRows("idempotency_key");
		long ledgerTransactionsBefore = countRows("ledger_transaction");
		long outboxEventsBefore = countRows("outbox_event");
		BigDecimal sourceBalanceBefore = fetchBalance(sourceA);

		// B reuses A's exact key string, targeting A's source account (which
		// B does not own) -- ownership must be checked before the
		// idempotency claim/replay, so B never sees A's stored response.
		ResponseEntity<Map> replayAttempt = postTransfer(sourceA, destinationA, "10.00", sharedKey, tokenB);

		assertThat(replayAttempt.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(replayAttempt.getBody().toString()).doesNotContain(String.valueOf(original.getBody().get("transactionId")));
		assertThat(countRows("idempotency_key")).isEqualTo(idempotencyRowsBefore);
		assertThat(countRows("ledger_transaction")).isEqualTo(ledgerTransactionsBefore);
		assertThat(countRows("outbox_event")).isEqualTo(outboxEventsBefore);
		assertThat(fetchBalance(sourceA)).isEqualByComparingTo(sourceBalanceBefore);
	}

	@Test
	void twoCustomersReusingTheIdenticalKeyStringAgainstTheirOwnAccountsAreCompletelyIndependent() {
		String tokenA = TestAuthSupport.customerAToken(restTemplate);
		String tokenB = TestAuthSupport.customerBToken(restTemplate);
		UUID accountA = createAccount(tokenA);
		UUID accountB = createAccount(tokenB);
		String sharedKey = UUID.randomUUID().toString();

		ResponseEntity<Map> depositA = postDeposit(accountA, "10.00", sharedKey, tokenA);
		ResponseEntity<Map> depositB = postDeposit(accountB, "20.00", sharedKey, tokenB);

		// Same literal key string, two different principals -- both are
		// genuinely new deposits (not a replay, not a 409 conflict),
		// because idempotency_key uniqueness is scoped per principal.
		assertThat(depositA.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(depositB.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(depositA.getBody().get("transactionId")).isNotEqualTo(depositB.getBody().get("transactionId"));
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private UUID createAccount(String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(token);
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/accounts",
				new HttpEntity<>(Map.of("ownerName", "Auth Test Owner " + UUID.randomUUID(), "currency", "USD"), headers),
				Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return UUID.fromString((String) response.getBody().get("id"));
	}

	private UUID createFundedAccount(String token, String amount) {
		UUID accountId = createAccount(token);
		ResponseEntity<Map> response = postDeposit(accountId, amount, UUID.randomUUID().toString(), token);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return accountId;
	}

	private ResponseEntity<Map> postDeposit(UUID accountId, String amount, String idempotencyKey, String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(token);
		headers.set("Idempotency-Key", idempotencyKey);
		return restTemplate.postForEntity("/api/v1/accounts/" + accountId + "/deposits",
				new HttpEntity<>(Map.of("amount", amount, "currency", "USD"), headers), Map.class);
	}

	private ResponseEntity<Map> postTransfer(UUID sourceId, UUID destinationId, String amount, String idempotencyKey,
			String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(token);
		headers.set("Idempotency-Key", idempotencyKey);
		Map<String, Object> body = Map.of("sourceAccountId", sourceId.toString(),
				"destinationAccountId", destinationId.toString(), "amount", amount, "currency", "USD");
		return restTemplate.postForEntity("/api/v1/transfers", new HttpEntity<>(body, headers), Map.class);
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

	private long countRows(String tableName) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
			resultSet.next();
			return resultSet.getLong(1);
		}
	}

}
