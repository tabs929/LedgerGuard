package com.tarun.ledgerguard.account;

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
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises account creation (Task 3) at the HTTP boundary and verifies the
 * persisted database state directly via JDBC, against a real, isolated
 * PostgreSQL 16.4 Testcontainer that runs the Flyway migrations from
 * scratch. No mocks are used for persistence behavior.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccountCreationIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	TestRestTemplate restTemplate;

	@Autowired
	DataSource dataSource;

	@Autowired
	AccountRepository accountRepository;

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	void createsValidUsdAccount_returnsExpectedResponseAndPersistsCorrectRow() throws SQLException {
		ResponseEntity<AccountResponse> response = postAccount(Map.of(
				"ownerName", "Alice Example",
				"currency", "USD"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		AccountResponse body = response.getBody();
		assertThat(body).isNotNull();
		assertThat(body.id()).isNotNull();
		assertThat(body.ownerName()).isEqualTo("Alice Example");
		assertThat(body.currency()).isEqualTo("USD");
		assertThat(body.balance()).isEqualByComparingTo("0.0000");
		assertThat(body.createdAt()).isNotNull();

		Map<String, Object> row = fetchAccountRow(body.id());
		assertThat(row.get("account_category")).isEqualTo("CUSTOMER");
		assertThat(row.get("account_class")).isEqualTo("LIABILITY");
		assertThat(row.get("account_purpose")).isEqualTo("CUSTOMER_WALLET");
		assertThat(row.get("currency")).isEqualTo("USD");
		assertThat(((BigDecimal) row.get("balance"))).isEqualByComparingTo("0.0000");
	}

	@Test
	void lowercaseUsdCurrencyIsNormalizedToUppercase() {
		ResponseEntity<AccountResponse> response = postAccount(Map.of(
				"ownerName", "Bob Example",
				"currency", "usd"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().currency()).isEqualTo("USD");
	}

	@Test
	void missingCurrencyIsRejectedAndNotPersisted() throws SQLException {
		long before = countAccountsWithOwnerName("Missing Currency Owner");

		ResponseEntity<String> response = postRaw(Map.of("ownerName", "Missing Currency Owner"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(countAccountsWithOwnerName("Missing Currency Owner")).isEqualTo(before);
	}

	@Test
	void malformedCurrencyIsRejectedAndNotPersisted() throws SQLException {
		long before = countAccountsWithOwnerName("Malformed Currency Owner");

		ResponseEntity<String> response = postRaw(Map.of(
				"ownerName", "Malformed Currency Owner",
				"currency", "US1"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(countAccountsWithOwnerName("Malformed Currency Owner")).isEqualTo(before);
	}

	@Test
	void unsupportedNonUsdCurrencyIsRejectedAndNotPersisted() throws SQLException {
		long before = countAccountsWithOwnerName("Unsupported Currency Owner");

		ResponseEntity<String> response = postRaw(Map.of(
				"ownerName", "Unsupported Currency Owner",
				"currency", "EUR"));

		assertThat(response.getStatusCode().value()).isEqualTo(422);
		assertThat(countAccountsWithOwnerName("Unsupported Currency Owner")).isEqualTo(before);
	}

	@Test
	void clientCannotCreateSystemAccountOrChooseProtectedFields() throws SQLException {
		long before = countAccountsWithOwnerName("Attempted System Account");

		Map<String, Object> attempt = new LinkedHashMap<>();
		attempt.put("ownerName", "Attempted System Account");
		attempt.put("currency", "USD");
		attempt.put("accountCategory", "SYSTEM");
		attempt.put("accountClass", "ASSET");
		attempt.put("accountPurpose", "EXTERNAL_FUNDING");
		attempt.put("balance", 1000000);
		attempt.put("id", "11111111-1111-1111-1111-111111111111");
		attempt.put("createdAt", "2000-01-01T00:00:00Z");

		ResponseEntity<String> response = postRaw(attempt);

		// Unknown JSON properties are rejected outright (400), not silently
		// dropped -- see spring.jackson.deserialization.fail-on-unknown-properties.
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(countAccountsWithOwnerName("Attempted System Account")).isEqualTo(before);
	}

	@Test
	void databaseTaxonomyConstraintRemainsActiveEvenWhenBypassingTheService() {
		// Proves the JPA mapping does not weaken or bypass the schema's
		// cross-column CHECK constraint: attempting to persist an invalid
		// (category, class, purpose) combination directly through the
		// repository still fails at the database.
		Account invalid = new Account(
				AccountCategory.CUSTOMER,
				AccountClass.ASSET,
				AccountPurpose.EXTERNAL_FUNDING,
				"Invalid Combo",
				"USD",
				BigDecimal.ZERO.setScale(4));

		assertThatThrownBy(() -> {
			accountRepository.saveAndFlush(invalid);
		}).hasMessageContaining("chk_account_taxonomy_combination");
	}

	private ResponseEntity<AccountResponse> postAccount(Map<String, Object> body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return restTemplate.postForEntity("/api/v1/accounts", new HttpEntity<>(body, headers), AccountResponse.class);
	}

	private ResponseEntity<String> postRaw(Map<String, Object> body) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		try {
			String json = JSON.writeValueAsString(body);
			return restTemplate.postForEntity("/api/v1/accounts", new HttpEntity<>(json, headers), String.class);
		}
		catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	private Map<String, Object> fetchAccountRow(java.util.UUID id) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT account_category, account_class, account_purpose, currency, balance "
								+ "FROM account WHERE id = ?")) {
			statement.setObject(1, id);
			try (ResultSet resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).isTrue();
				Map<String, Object> row = new LinkedHashMap<>();
				row.put("account_category", resultSet.getString("account_category"));
				row.put("account_class", resultSet.getString("account_class"));
				row.put("account_purpose", resultSet.getString("account_purpose"));
				row.put("currency", resultSet.getString("currency"));
				row.put("balance", resultSet.getBigDecimal("balance"));
				return row;
			}
		}
	}

	private long countAccountsWithOwnerName(String ownerName) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM account WHERE owner_name = ?")) {
			statement.setString(1, ownerName);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getLong(1);
			}
		}
	}

}
