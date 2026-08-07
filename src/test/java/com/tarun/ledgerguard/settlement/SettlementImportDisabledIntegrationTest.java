package com.tarun.ledgerguard.settlement;

import com.tarun.ledgerguard.security.TestAuthSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@code ledgerguard.settlement.import.enabled=false} rejects
 * every import predictably (503) without affecting any other endpoint --
 * a separate {@code @SpringBootTest} context from
 * {@link SettlementImportIntegrationTest} since this property is normally
 * {@code true} and must be overridden for the whole test class.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = "ledgerguard.settlement.import.enabled=false")
class SettlementImportDisabledIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	TestRestTemplate restTemplate;

	@Test
	void importRequestIsRejectedWithAnExplicitResponseWhenDisabled() {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("source", "acme-bank");
		body.add("file", new ByteArrayResource(
				("external_reference,transaction_id,amount,currency,settled_at\nEXT-1," + UUID.randomUUID()
						+ ",10.00,USD,2026-07-15T10:00:00Z\n").getBytes(StandardCharsets.UTF_8)) {
			@Override
			public String getFilename() {
				return "settlement.csv";
			}
		});
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		headers.setBearerAuth(TestAuthSupport.operationsToken(restTemplate));

		ResponseEntity<Map> response = restTemplate.postForEntity(
				"/api/v1/settlement-imports", new HttpEntity<>(body, headers), Map.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
	}

	@Test
	void otherEndpointsAreUnaffectedWhenSettlementImportIsDisabled() {
		Map<String, Object> accountBody = Map.of("ownerName", "Disabled Settlement Owner", "currency", "USD");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(TestAuthSupport.customerAToken(restTemplate));
		ResponseEntity<Map> response = restTemplate.postForEntity(
				"/api/v1/accounts", new HttpEntity<>(accountBody, headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

}
