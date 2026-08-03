package com.tarun.ledgerguard.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the plain HTML/CSS/JS demonstration UI added under
 * {@code src/main/resources/static/} is served correctly by Spring Boot's
 * default static-resource handling, and — just as importantly — that it
 * does not shadow any existing REST, actuator, or Swagger/OpenAPI path.
 * The UI itself has no server-side code of its own to unit test; this is
 * the "focused test appropriate for static Spring Boot resources" the
 * task calls for. Against a real, isolated PostgreSQL 16.4 Testcontainer,
 * like every other integration test in this project — the application
 * requires a working datasource to start at all.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StaticUiIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	TestRestTemplate restTemplate;

	@Test
	void rootReturnsTheUiSuccessfully() {
		ResponseEntity<String> response = restTemplate.getForEntity("/", String.class);

		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(response.getHeaders().getContentType()).isNotNull();
		assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
		assertThat(response.getBody()).contains("LedgerGuard").contains("<script src=\"/app.js\"");
	}

	@Test
	void indexHtmlIsAlsoReachableDirectly() {
		ResponseEntity<String> response = restTemplate.getForEntity("/index.html", String.class);
		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
	}

	@Test
	void stylesCssIsAvailable() {
		ResponseEntity<String> response = restTemplate.getForEntity("/styles.css", String.class);

		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(response.getHeaders().getContentType()).isNotNull();
		assertThat(response.getHeaders().getContentType().toString()).contains("css");
		assertThat(response.getBody()).isNotBlank();
	}

	@Test
	void appJsIsAvailable() {
		ResponseEntity<String> response = restTemplate.getForEntity("/app.js", String.class);

		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(response.getHeaders().getContentType()).isNotNull();
		assertThat(response.getHeaders().getContentType().toString()).containsAnyOf("javascript", "ecmascript");
		assertThat(response.getBody()).isNotBlank();
	}

	@Test
	void appJsContainsNoInlineEventHandlerMarkersAndUsesRelativeApiUrls() {
		ResponseEntity<String> response = restTemplate.getForEntity("/app.js", String.class);
		String body = response.getBody();
		assertThat(body).contains("/api/v1/accounts");
		assertThat(body).contains("/api/v1/settlement-imports");
		assertThat(body).contains("/actuator/health");
	}

	// ------------------------------------------------------------------
	// the static UI must not shadow any existing path
	// ------------------------------------------------------------------

	@Test
	void actuatorHealthStillReturnsJsonNotTheUi() {
		ResponseEntity<Map> response = restTemplate.getForEntity("/actuator/health", Map.class);

		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(response.getHeaders().getContentType()).isNotNull();
		assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
		assertThat(response.getBody()).containsKey("status");
	}

	@Test
	void openApiDocumentStillReturnsJsonNotTheUi() {
		ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(response.getHeaders().getContentType()).isNotNull();
		assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
		assertThat(response.getBody()).doesNotContain("<script src=\"/app.js\"");
	}

	@Test
	void swaggerUiStillReachable() {
		ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui/index.html", String.class);

		assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
		assertThat(response.getHeaders().getContentType()).isNotNull();
		assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
	}

	@Test
	void accountCreationEndpointStillWorksExactlyAsBefore() {
		Map<String, Object> body = Map.of("ownerName", "Static UI Regression Owner", "currency", "USD");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map> response = restTemplate.postForEntity(
				"/api/v1/accounts", new HttpEntity<>(body, headers), Map.class);

		assertThat(response.getStatusCode().value()).isEqualTo(201);
		assertThat(response.getHeaders().getContentType()).isNotNull();
		assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
		assertThat(response.getBody()).containsKeys("id", "ownerName", "currency", "balance", "createdAt");
	}

	@Test
	void unknownApiPathStillReturns404NotTheUiFallback() {
		ResponseEntity<String> response = restTemplate.getForEntity(
				"/api/v1/definitely-not-a-real-endpoint-" + UUID.randomUUID(), String.class);

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		String responseBody = response.getBody() == null ? "" : response.getBody();
		assertThat(responseBody).doesNotContain("<script src=\"/app.js\"");
	}

	@Test
	void existingErrorEnvelopeIsUnchanged() {
		ResponseEntity<Map> response = restTemplate.getForEntity(
				"/api/v1/accounts/" + UUID.randomUUID() + "/balance", Map.class);

		assertThat(response.getStatusCode().value()).isEqualTo(404);
		assertThat(response.getBody().keySet()).containsExactlyInAnyOrder(
				"timestamp", "status", "error", "message", "path");
	}

}
