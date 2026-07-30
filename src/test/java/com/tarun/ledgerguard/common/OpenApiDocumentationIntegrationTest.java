package com.tarun.ledgerguard.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the OpenAPI document and Swagger UI (Task 8) against the real,
 * running application, and cross-checks the generated contract against
 * the actual implemented endpoints. This project requires a working
 * PostgreSQL datasource to start at all (JPA + Flyway are mandatory
 * auto-configuration), so — like every other integration test in this
 * project — this runs against a real, isolated PostgreSQL 16.4
 * Testcontainer running the Flyway migrations from scratch, never H2 and
 * never the local Docker Compose database.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocumentationIntegrationTest {

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
			"org.postgresql", "com.zaxxer", "SQLState", "Caused by", "chk_", "idx_",
			"ledger_entry", "ledger_transaction", "password", "credential", "localhost:5432");

	// ------------------------------------------------------------------
	// OpenAPI document availability
	// ------------------------------------------------------------------

	@Test
	void openApiDocumentReturns200WithJsonMediaType() {
		ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(response.getHeaders().getContentType()).isNotNull();
		assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
	}

	@Test
	void openApiDocumentDeclaresOpenApi3() throws Exception {
		JsonNode root = fetchDocument();
		assertThat(root.get("openapi").asText()).startsWith("3.");
	}

	@Test
	void openApiDocumentHasRequiredMetadata() throws Exception {
		JsonNode info = fetchDocument().get("info");

		assertThat(info.get("title").asText()).isEqualTo("LedgerGuard API");
		assertThat(info.get("version").asText()).isNotBlank();
		String description = info.get("description").asText();
		assertThat(description).contains("double-entry").contains("USD deposits")
				.contains("USD customer-to-customer transfers").contains("materialized balance")
				.contains("transaction-history");
	}

	@Test
	void swaggerUiIsReachableAndReferencesTheGeneratedDocument() {
		ResponseEntity<String> ui = restTemplate.getForEntity("/swagger-ui/index.html", String.class);
		assertThat(ui.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(ui.getHeaders().getContentType()).isNotNull();
		assertThat(ui.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();

		// The conventional short-form redirect springdoc also registers.
		ResponseEntity<String> redirect = restTemplate.getForEntity("/swagger-ui.html", String.class);
		assertThat(redirect.getStatusCode().is2xxSuccessful()).isTrue();
	}

	// ------------------------------------------------------------------
	// endpoint coverage
	// ------------------------------------------------------------------

	@Test
	void everyImplementedEndpointIsDocumentedWithTheCorrectMethod() throws Exception {
		JsonNode paths = fetchDocument().get("paths");

		assertThat(paths.has("/api/v1/accounts")).isTrue();
		assertThat(paths.get("/api/v1/accounts").has("post")).isTrue();

		assertThat(paths.has("/api/v1/accounts/{id}/deposits")).isTrue();
		assertThat(paths.get("/api/v1/accounts/{id}/deposits").has("post")).isTrue();

		assertThat(paths.has("/api/v1/transfers")).isTrue();
		assertThat(paths.get("/api/v1/transfers").has("post")).isTrue();

		assertThat(paths.has("/api/v1/accounts/{id}/balance")).isTrue();
		assertThat(paths.get("/api/v1/accounts/{id}/balance").has("get")).isTrue();

		assertThat(paths.has("/api/v1/accounts/{id}/transactions")).isTrue();
		assertThat(paths.get("/api/v1/accounts/{id}/transactions").has("get")).isTrue();
	}

	@Test
	void deferredAndUnimplementedEndpointsAreAbsent() throws Exception {
		JsonNode paths = fetchDocument().get("paths");

		// Plain GET /api/v1/accounts/{id} is not its own path template in
		// this API -- every account-scoped operation is nested under
		// /deposits, /balance, or /transactions. If it ever appeared as a
		// bare path, that would mean it had been (re)implemented outside
		// any approved task.
		assertThat(paths.has("/api/v1/accounts/{id}")).isFalse();

		Iterator<String> pathNames = paths.fieldNames();
		Set<String> allPaths = Set.of(
				"/api/v1/accounts", "/api/v1/accounts/{id}/deposits", "/api/v1/transfers",
				"/api/v1/accounts/{id}/balance", "/api/v1/accounts/{id}/transactions");
		while (pathNames.hasNext()) {
			String path = pathNames.next();
			if (path.startsWith("/api/v1/")) {
				assertThat(allPaths).as("undocumented business path found: %s", path).contains(path);
			}
		}
	}

	// ------------------------------------------------------------------
	// schema accuracy
	// ------------------------------------------------------------------

	@Test
	void requestAndResponseSchemasExistWithCorrectRequiredFields() throws Exception {
		JsonNode schemas = fetchDocument().get("components").get("schemas");

		assertThat(schemas.has("CreateAccountRequest")).isTrue();
		assertThat(requiredFields(schemas.get("CreateAccountRequest"))).containsExactlyInAnyOrder("ownerName", "currency");

		assertThat(schemas.has("DepositRequest")).isTrue();
		assertThat(requiredFields(schemas.get("DepositRequest"))).containsExactlyInAnyOrder("amount", "currency");

		assertThat(schemas.has("TransferRequest")).isTrue();
		assertThat(requiredFields(schemas.get("TransferRequest")))
				.containsExactlyInAnyOrder("sourceAccountId", "destinationAccountId", "amount", "currency");

		assertThat(schemas.has("AccountResponse")).isTrue();
		assertThat(schemas.has("DepositResponse")).isTrue();
		assertThat(schemas.has("TransferResponse")).isTrue();
		assertThat(schemas.has("AccountBalanceResponse")).isTrue();
		assertThat(schemas.has("TransactionHistoryItem")).isTrue();
	}

	@Test
	void uuidFieldsUseStringUuidFormat() throws Exception {
		JsonNode schemas = fetchDocument().get("components").get("schemas");

		JsonNode sourceId = schemas.get("TransferRequest").get("properties").get("sourceAccountId");
		assertThat(sourceId.get("type").asText()).isEqualTo("string");
		assertThat(sourceId.get("format").asText()).isEqualTo("uuid");

		JsonNode accountId = schemas.get("AccountBalanceResponse").get("properties").get("accountId");
		assertThat(accountId.get("type").asText()).isEqualTo("string");
		assertThat(accountId.get("format").asText()).isEqualTo("uuid");
	}

	@Test
	void monetaryFieldsUseDecimalCompatibleStringSchemasNotFloatingPoint() throws Exception {
		JsonNode schemas = fetchDocument().get("components").get("schemas");

		for (String[] schemaAndField : new String[][] {
				{ "DepositRequest", "amount" }, { "TransferRequest", "amount" },
				{ "AccountResponse", "balance" }, { "AccountBalanceResponse", "balance" },
				{ "DepositResponse", "amount" }, { "DepositResponse", "newBalance" },
				{ "TransferResponse", "amount" }, { "TransactionHistoryItem", "amount" } }) {
			JsonNode field = schemas.get(schemaAndField[0]).get("properties").get(schemaAndField[1]);
			assertThat(field.get("type").asText())
					.as("%s.%s should be a decimal string, not a float/double", schemaAndField[0], schemaAndField[1])
					.isEqualTo("string");
			assertThat(field.has("format") ? field.get("format").asText() : "")
					.as("%s.%s must not use a floating-point format", schemaAndField[0], schemaAndField[1])
					.isNotIn("float", "double");
		}
	}

	@Test
	void enumValuesMatchTheImplementedJavaEnum() throws Exception {
		JsonNode schemas = fetchDocument().get("components").get("schemas");
		JsonNode entryType = schemas.get("TransactionHistoryItem").get("properties").get("entryType");

		List<String> values = new java.util.ArrayList<>();
		entryType.get("enum").forEach(n -> values.add(n.asText()));
		assertThat(values).containsExactlyInAnyOrder("DEBIT", "CREDIT");
	}

	@Test
	void protectedAndServerControlledFieldsAreAbsentFromRequestSchemas() throws Exception {
		JsonNode schemas = fetchDocument().get("components").get("schemas");
		Set<String> protectedFields = Set.of(
				"id", "accountId", "transactionId", "balance", "newBalance", "createdAt",
				"accountCategory", "accountClass", "accountPurpose", "status", "entryType");

		for (String requestSchemaName : List.of("CreateAccountRequest", "DepositRequest", "TransferRequest")) {
			JsonNode properties = schemas.get(requestSchemaName).get("properties");
			Iterator<String> fieldNames = properties.fieldNames();
			while (fieldNames.hasNext()) {
				String field = fieldNames.next();
				assertThat(protectedFields).as("%s must not expose protected field %s", requestSchemaName, field)
						.doesNotContain(field);
			}
		}
	}

	@Test
	void noJpaEntityOrImplementationLeakingSchemaNamesArePublished() throws Exception {
		JsonNode schemas = fetchDocument().get("components").get("schemas");
		Iterator<String> names = schemas.fieldNames();
		while (names.hasNext()) {
			String name = names.next();
			assertThat(name).doesNotContain("Account$").doesNotContain("LedgerEntry$").doesNotContain("LedgerTransaction$");
			assertThat(name).doesNotContainIgnoringCase("proxy").doesNotContainIgnoringCase("hibernate");
			assertThat(name).isNotEqualTo("PageImpl");
		}
		// The JPA entities themselves are never published as schemas.
		assertThat(schemas.has("Account")).isFalse();
		assertThat(schemas.has("LedgerEntry")).isFalse();
		assertThat(schemas.has("LedgerTransaction")).isFalse();
	}

	@Test
	void sharedErrorSchemaMatchesTheExactImplementedEnvelope() throws Exception {
		JsonNode schemas = fetchDocument().get("components").get("schemas");
		assertThat(schemas.has("ApiError")).isTrue();

		Iterator<String> fieldNames = schemas.get("ApiError").get("properties").fieldNames();
		Set<String> fields = new java.util.HashSet<>();
		fieldNames.forEachRemaining(fields::add);
		assertThat(fields).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path");
	}

	// ------------------------------------------------------------------
	// response documentation
	// ------------------------------------------------------------------

	@Test
	void documentedSuccessStatusesMatchControllerBehavior() throws Exception {
		JsonNode paths = fetchDocument().get("paths");

		assertThat(responseCodes(paths, "/api/v1/accounts", "post")).contains("201");
		assertThat(responseCodes(paths, "/api/v1/accounts/{id}/deposits", "post")).contains("201");
		assertThat(responseCodes(paths, "/api/v1/transfers", "post")).contains("201");
		assertThat(responseCodes(paths, "/api/v1/accounts/{id}/balance", "get")).contains("200");
		assertThat(responseCodes(paths, "/api/v1/accounts/{id}/transactions", "get")).contains("200");
	}

	@Test
	void documentedErrorStatusesMatchApiSpecAndTask7Mappings() throws Exception {
		JsonNode paths = fetchDocument().get("paths");

		assertThat(responseCodes(paths, "/api/v1/accounts", "post")).containsExactlyInAnyOrder("201", "400", "422");
		assertThat(responseCodes(paths, "/api/v1/accounts/{id}/deposits", "post"))
				.containsExactlyInAnyOrder("201", "400", "404", "422");
		assertThat(responseCodes(paths, "/api/v1/transfers", "post")).containsExactlyInAnyOrder("201", "400", "404", "422");
		assertThat(responseCodes(paths, "/api/v1/accounts/{id}/balance", "get")).containsExactlyInAnyOrder("200", "404");
		assertThat(responseCodes(paths, "/api/v1/accounts/{id}/transactions", "get"))
				.containsExactlyInAnyOrder("200", "400", "404");
	}

	@Test
	void everyResponseUsesApplicationJsonMediaType() throws Exception {
		JsonNode paths = fetchDocument().get("paths");
		for (String path : List.of("/api/v1/accounts", "/api/v1/accounts/{id}/deposits", "/api/v1/transfers",
				"/api/v1/accounts/{id}/balance", "/api/v1/accounts/{id}/transactions")) {
			Iterator<Map.Entry<String, JsonNode>> methods = paths.get(path).fields();
			while (methods.hasNext()) {
				Map.Entry<String, JsonNode> method = methods.next();
				Iterator<Map.Entry<String, JsonNode>> responses = method.getValue().get("responses").fields();
				while (responses.hasNext()) {
					JsonNode content = responses.next().getValue().get("content");
					assertThat(content.fieldNames()).toIterable().containsExactly("application/json");
				}
			}
		}
	}

	@Test
	void transactionHistoryUsesTheCustomPaginationEnvelopeNotSpringDataPage() throws Exception {
		JsonNode root = fetchDocument();
		JsonNode okResponse = root.get("paths").get("/api/v1/accounts/{id}/transactions").get("get")
				.get("responses").get("200");
		String schemaRef = okResponse.get("content").get("application/json").get("schema").get("$ref").asText();
		String schemaName = schemaRef.substring(schemaRef.lastIndexOf('/') + 1);

		JsonNode pageSchema = root.get("components").get("schemas").get(schemaName);
		Set<String> properties = new java.util.HashSet<>();
		pageSchema.get("properties").fieldNames().forEachRemaining(properties::add);
		assertThat(properties).containsExactlyInAnyOrder("content", "page", "size", "totalElements", "totalPages");

		// Never Spring Data's own Page shape.
		assertThat(schemaName).doesNotContain("PageImpl");
		assertThat(root.get("components").get("schemas").has("PageImpl")).isFalse();

		// content[] items reference TransactionHistoryItem, not an
		// unresolved/anonymous schema.
		JsonNode itemsSchema = pageSchema.get("properties").get("content").get("items");
		assertThat(itemsSchema.has("$ref")).isTrue();
		assertThat(itemsSchema.get("$ref").asText()).endsWith("/TransactionHistoryItem");
	}

	@Test
	void paginationParametersDocumentDefaultsAndBounds() throws Exception {
		JsonNode parameters = fetchDocument().get("paths").get("/api/v1/accounts/{id}/transactions").get("get")
				.get("parameters");

		JsonNode page = findParameter(parameters, "page");
		assertThat(page.get("schema").get("default").asInt()).isEqualTo(0);
		assertThat(page.get("schema").get("minimum").asInt()).isEqualTo(0);

		JsonNode size = findParameter(parameters, "size");
		assertThat(size.get("schema").get("default").asInt()).isEqualTo(20);
		assertThat(size.get("schema").get("minimum").asInt()).isEqualTo(1);
		assertThat(size.get("schema").get("maximum").asInt()).isEqualTo(100);
	}

	@Test
	void newestFirstHistoryOrderingIsDescribed() throws Exception {
		String description = fetchDocument().get("paths").get("/api/v1/accounts/{id}/transactions").get("get")
				.get("description").asText();
		assertThat(description).contains("created_at DESC").contains("tie-breaker");
	}

	// ------------------------------------------------------------------
	// safety
	// ------------------------------------------------------------------

	@Test
	void openApiDocumentContainsNoInternalImplementationDetails() throws Exception {
		String raw = restTemplate.getForEntity("/v3/api-docs", String.class).getBody();
		assertThat(raw).isNotNull();
		for (String forbidden : FORBIDDEN_SNIPPETS) {
			assertThat(raw).as("OpenAPI document should not contain '%s'", forbidden).doesNotContain(forbidden);
		}
	}

	@Test
	void noAuthenticationSchemeIsDeclared() throws Exception {
		JsonNode root = fetchDocument();
		JsonNode components = root.get("components");
		boolean hasSecuritySchemes = components != null && components.has("securitySchemes")
				&& components.get("securitySchemes").size() > 0;
		assertThat(hasSecuritySchemes).isFalse();
		assertThat(root.has("security")).isFalse();
	}

	@Test
	void systemAccountOperationsAreNotPubliclyDocumented() throws Exception {
		// Mentioning EXTERNAL_FUNDING in explanatory prose (matching
		// docs/API_SPEC.md's own wording, e.g. "DEBITs the internal
		// EXTERNAL_FUNDING asset account") is not the same as exposing it as
		// something a client can act on. What must never appear is a way to
		// *supply* a system account: no request property for it, and no
		// endpoint/path scoped to it.
		JsonNode schemas = fetchDocument().get("components").get("schemas");
		for (String requestSchemaName : List.of("CreateAccountRequest", "DepositRequest", "TransferRequest")) {
			Iterator<String> fieldNames = schemas.get(requestSchemaName).get("properties").fieldNames();
			while (fieldNames.hasNext()) {
				String field = fieldNames.next();
				assertThat(field.toLowerCase())
						.as("%s must not expose a client-suppliable funding/system account field", requestSchemaName)
						.doesNotContain("funding").doesNotContain("system");
			}
		}

		JsonNode paths = fetchDocument().get("paths");
		Iterator<String> pathNames = paths.fieldNames();
		while (pathNames.hasNext()) {
			String path = pathNames.next();
			assertThat(path.toLowerCase()).doesNotContain("funding").doesNotContain("system-account");
		}
	}

	// ------------------------------------------------------------------
	// regression
	// ------------------------------------------------------------------

	@Test
	void coreEndpointsStillFunctionCorrectlyAlongsideDocumentation() throws SQLException {
		Map<String, Object> accountBody = Map.of("ownerName", "OpenAPI Regression Owner", "currency", "USD");
		ResponseEntity<Map> accountResponse = restTemplate.postForEntity("/api/v1/accounts", jsonEntity(accountBody), Map.class);
		assertThat(accountResponse.getStatusCode().value()).isEqualTo(201);
		UUID accountId = UUID.fromString((String) accountResponse.getBody().get("id"));

		Map<String, Object> depositBody = Map.of("amount", "40.00", "currency", "USD");
		ResponseEntity<Map> depositResponse = restTemplate.postForEntity(
				"/api/v1/accounts/" + accountId + "/deposits", jsonEntity(depositBody), Map.class);
		assertThat(depositResponse.getStatusCode().value()).isEqualTo(201);

		ResponseEntity<Map> balanceResponse = restTemplate.getForEntity(
				"/api/v1/accounts/" + accountId + "/balance", Map.class);
		assertThat(balanceResponse.getStatusCode().value()).isEqualTo(200);
		assertThat(new BigDecimal(String.valueOf(balanceResponse.getBody().get("balance"))))
				.isEqualByComparingTo("40.00");

		ResponseEntity<Map> historyResponse = restTemplate.getForEntity(
				"/api/v1/accounts/" + accountId + "/transactions", Map.class);
		assertThat(historyResponse.getStatusCode().value()).isEqualTo(200);
		assertThat((List<?>) historyResponse.getBody().get("content")).hasSize(1);

		// Global error handling still returns the shared envelope, unchanged.
		ResponseEntity<Map> notFound = restTemplate.getForEntity(
				"/api/v1/accounts/" + UUID.randomUUID() + "/balance", Map.class);
		assertThat(notFound.getStatusCode().value()).isEqualTo(404);
		assertThat(notFound.getBody().keySet()).containsExactlyInAnyOrder("timestamp", "status", "error", "message", "path");
	}

	@Test
	void ledgerImmutabilityTriggersStillRejectUpdateAndDelete() throws SQLException {
		Map<String, Object> accountBody = Map.of("ownerName", "OpenAPI Immutability Owner", "currency", "USD");
		ResponseEntity<Map> accountResponse = restTemplate.postForEntity("/api/v1/accounts", jsonEntity(accountBody), Map.class);
		UUID accountId = UUID.fromString((String) accountResponse.getBody().get("id"));

		Map<String, Object> depositBody = Map.of("amount", "5.00", "currency", "USD");
		ResponseEntity<Map> depositResponse = restTemplate.postForEntity(
				"/api/v1/accounts/" + accountId + "/deposits", jsonEntity(depositBody), Map.class);
		UUID transactionId = UUID.fromString((String) depositResponse.getBody().get("transactionId"));

		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"UPDATE ledger_transaction SET status = 'COMPLETED' WHERE id = ?")) {
			statement.setObject(1, transactionId);
			assertThatRejected(statement);
		}
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private JsonNode fetchDocument() throws Exception {
		String raw = restTemplate.getForEntity("/v3/api-docs", String.class).getBody();
		return JSON.readTree(raw);
	}

	private List<String> requiredFields(JsonNode schema) {
		List<String> required = new java.util.ArrayList<>();
		if (schema.has("required")) {
			schema.get("required").forEach(n -> required.add(n.asText()));
		}
		return required;
	}

	private List<String> responseCodes(JsonNode paths, String path, String method) {
		List<String> codes = new java.util.ArrayList<>();
		paths.get(path).get(method).get("responses").fieldNames().forEachRemaining(codes::add);
		return codes;
	}

	private JsonNode findParameter(JsonNode parameters, String name) {
		for (JsonNode parameter : parameters) {
			if (parameter.get("name").asText().equals(name)) {
				return parameter;
			}
		}
		throw new AssertionError("parameter not found: " + name);
	}

	private org.springframework.http.HttpEntity<Map<String, Object>> jsonEntity(Map<String, Object> body) {
		org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		return new org.springframework.http.HttpEntity<>(body, headers);
	}

	private void assertThatRejected(PreparedStatement statement) throws SQLException {
		try {
			statement.executeUpdate();
			throw new AssertionError("Expected immutability trigger to reject the statement");
		}
		catch (SQLException expected) {
			assertThat(expected.getMessage()).contains("immutable");
		}
	}

}
