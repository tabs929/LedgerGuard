package com.tarun.ledgerguard.reconciliation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the OpenAPI document for the three Task 15 endpoints
 * specifically: documented status codes, the shared {@code ApiError}
 * schema for error responses, and that no extra settlement/reconciliation
 * endpoint exists.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconciliationOpenApiIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	TestRestTemplate restTemplate;

	private static final ObjectMapper JSON = new ObjectMapper();

	private JsonNode fetchDocument() throws Exception {
		String raw = restTemplate.getForEntity("/v3/api-docs", String.class).getBody();
		return JSON.readTree(raw);
	}

	@Test
	void allThreeReconciliationEndpointsAreDocumented() throws Exception {
		JsonNode paths = fetchDocument().get("paths");
		assertThat(paths.has("/api/v1/settlement-imports/{importId}/reconciliation")).isTrue();
		assertThat(paths.get("/api/v1/settlement-imports/{importId}/reconciliation").has("post")).isTrue();
		assertThat(paths.get("/api/v1/settlement-imports/{importId}/reconciliation").has("get")).isTrue();
		assertThat(paths.has("/api/v1/settlement-imports/{importId}/reconciliation/results")).isTrue();
		assertThat(paths.get("/api/v1/settlement-imports/{importId}/reconciliation/results").has("get")).isTrue();
	}

	@Test
	void commandEndpointDocumentsExactlyItsStatusCodes() throws Exception {
		JsonNode responses = fetchDocument().get("paths").get("/api/v1/settlement-imports/{importId}/reconciliation")
				.get("post").get("responses");
		Set<String> codes = new HashSet<>();
		responses.fieldNames().forEachRemaining(codes::add);
		assertThat(codes).containsExactlyInAnyOrder("201", "200", "400", "404");
	}

	@Test
	void getRunDocumentsExactlyItsStatusCodes() throws Exception {
		JsonNode responses = fetchDocument().get("paths").get("/api/v1/settlement-imports/{importId}/reconciliation")
				.get("get").get("responses");
		Set<String> codes = new HashSet<>();
		responses.fieldNames().forEachRemaining(codes::add);
		assertThat(codes).containsExactlyInAnyOrder("200", "400", "404");
	}

	@Test
	void getResultsDocumentsExactlyItsStatusCodes() throws Exception {
		JsonNode responses = fetchDocument().get("paths")
				.get("/api/v1/settlement-imports/{importId}/reconciliation/results").get("get").get("responses");
		Set<String> codes = new HashSet<>();
		responses.fieldNames().forEachRemaining(codes::add);
		assertThat(codes).containsExactlyInAnyOrder("200", "400", "404");
	}

	@Test
	void errorResponsesUseTheSharedApiErrorEnvelope() throws Exception {
		JsonNode postResponses = fetchDocument().get("paths").get("/api/v1/settlement-imports/{importId}/reconciliation")
				.get("post").get("responses");
		for (String status : new String[] { "400", "404" }) {
			String schemaRef = postResponses.get(status).get("content").get("application/json").get("schema")
					.get("$ref").asText();
			assertThat(schemaRef).endsWith("/ApiError");
		}
	}

	@Test
	void noOtherSettlementOrReconciliationEndpointExists() throws Exception {
		JsonNode paths = fetchDocument().get("paths");
		Set<String> expected = Set.of(
				"/api/v1/settlement-imports",
				"/api/v1/settlement-imports/{importId}/reconciliation",
				"/api/v1/settlement-imports/{importId}/reconciliation/results");
		paths.fieldNames().forEachRemaining(path -> {
			if (path.startsWith("/api/v1/settlement")) {
				assertThat(expected).contains(path);
			}
		});
	}

}
