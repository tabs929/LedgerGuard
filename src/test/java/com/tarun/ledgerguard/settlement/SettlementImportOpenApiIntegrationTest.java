package com.tarun.ledgerguard.settlement;

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

import java.util.Iterator;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the OpenAPI document for the new Task 14 endpoint specifically:
 * multipart content type, documented status codes, and the shared
 * {@code ApiError} schema for error responses.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettlementImportOpenApiIntegrationTest {

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
	void settlementImportEndpointIsDocumented() throws Exception {
		JsonNode paths = fetchDocument().get("paths");
		assertThat(paths.has("/api/v1/settlement-imports")).isTrue();
		assertThat(paths.get("/api/v1/settlement-imports").has("post")).isTrue();
	}

	@Test
	void requestBodyDeclaresMultipartFormDataContentType() throws Exception {
		JsonNode operation = fetchDocument().get("paths").get("/api/v1/settlement-imports").get("post");

		// springdoc documents the MultipartFile part as the binary
		// multipart/form-data request body, and the plain String "source"
		// part as a required form parameter -- two different generated
		// shapes for the same underlying multipart contract.
		JsonNode content = operation.get("requestBody").get("content");
		assertThat(content.has("multipart/form-data")).isTrue();
		JsonNode fileSchema = content.get("multipart/form-data").get("schema");
		assertThat(fileSchema.get("type").asText()).isEqualTo("string");
		assertThat(fileSchema.get("format").asText()).isEqualTo("binary");

		JsonNode sourceParameter = findParameter(operation.get("parameters"), "source");
		assertThat(sourceParameter.get("required").asBoolean()).isTrue();
		assertThat(sourceParameter.get("schema").get("type").asText()).isEqualTo("string");
	}

	private JsonNode findParameter(JsonNode parameters, String name) {
		for (JsonNode parameter : parameters) {
			if (parameter.get("name").asText().equals(name)) {
				return parameter;
			}
		}
		throw new AssertionError("parameter not found: " + name);
	}

	@Test
	void documentedStatusCodesMatchTheImplementedContract() throws Exception {
		JsonNode responses = fetchDocument().get("paths").get("/api/v1/settlement-imports").get("post").get("responses");
		Set<String> codes = new java.util.HashSet<>();
		responses.fieldNames().forEachRemaining(codes::add);
		assertThat(codes).containsExactlyInAnyOrder("201", "200", "400", "409", "413", "415");
	}

	@Test
	void errorResponsesUseTheSharedApiErrorEnvelope() throws Exception {
		JsonNode responses = fetchDocument().get("paths").get("/api/v1/settlement-imports").get("post").get("responses");
		for (String status : new String[] { "400", "409" }) {
			if (responses.has(status) && responses.get(status).has("content")) {
				JsonNode schemaRef = responses.get(status).get("content").path("application/json").path("schema").path("$ref");
				if (!schemaRef.isMissingNode()) {
					assertThat(schemaRef.asText()).endsWith("/ApiError");
				}
			}
		}
	}

	@Test
	void noSettlementCrudOrReconciliationEndpointExists() throws Exception {
		JsonNode paths = fetchDocument().get("paths");
		Iterator<String> pathNames = paths.fieldNames();
		while (pathNames.hasNext()) {
			String path = pathNames.next();
			if (path.startsWith("/api/v1/settlement")) {
				assertThat(path).isEqualTo("/api/v1/settlement-imports");
				JsonNode operations = paths.get(path);
				assertThat(operations.has("get")).isFalse();
				assertThat(operations.has("put")).isFalse();
				assertThat(operations.has("delete")).isFalse();
				assertThat(operations.has("patch")).isFalse();
			}
			assertThat(path.toLowerCase()).doesNotContain("reconcil");
		}
	}

}
