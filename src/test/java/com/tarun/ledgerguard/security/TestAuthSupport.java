package com.tarun.ledgerguard.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Shared test-only authentication helper (Task 17). Every integration test
 * that calls a now-protected endpoint authenticates through the real
 * {@code POST /api/v1/auth/token} endpoint using one of the isolated
 * test-only identities configured in {@code application-test.yml} --
 * never a security bypass, never the demo/dev identities from
 * {@code application.yml}.
 *
 * <p>Two distinct CUSTOMER identities exist ({@code test-customer-a} and
 * {@code test-customer-b}) specifically so cross-principal ownership and
 * idempotency-isolation tests have two real, independently authenticatable
 * principals to use.
 */
public final class TestAuthSupport {

	public static final String CUSTOMER_A_USERNAME = "test-customer-a";
	public static final String CUSTOMER_A_PASSWORD = "test-customer-a-password";
	public static final String CUSTOMER_B_USERNAME = "test-customer-b";
	public static final String CUSTOMER_B_PASSWORD = "test-customer-b-password";
	public static final String OPERATIONS_USERNAME = "test-operations";
	public static final String OPERATIONS_PASSWORD = "test-operations-password";

	// Must match application-test.yml's ledgerguard.security.jwt.signing-key
	// exactly -- used to craft deliberately malformed/expired/wrong-claim
	// tokens for negative authentication tests, signed with the SAME key
	// the running application actually validates against (so only the
	// claims/timestamps under test are wrong, not the signature itself,
	// except where a wrong-signature test deliberately uses a different key).
	public static final String TEST_SIGNING_KEY_BASE64 = "GSbnYa+DKBQUZstOdoVhhGd5qENdvwqhdeIBGpbmv9g=";
	public static final String TEST_ISSUER = "ledgerguard";
	public static final String TEST_AUDIENCE = "ledgerguard-api";

	private TestAuthSupport() {
	}

	public static HttpHeaders customerAHeaders(TestRestTemplate restTemplate) {
		return bearerHeaders(restTemplate, CUSTOMER_A_USERNAME, CUSTOMER_A_PASSWORD);
	}

	public static HttpHeaders customerBHeaders(TestRestTemplate restTemplate) {
		return bearerHeaders(restTemplate, CUSTOMER_B_USERNAME, CUSTOMER_B_PASSWORD);
	}

	public static HttpHeaders operationsHeaders(TestRestTemplate restTemplate) {
		return bearerHeaders(restTemplate, OPERATIONS_USERNAME, OPERATIONS_PASSWORD);
	}

	public static String customerAToken(TestRestTemplate restTemplate) {
		return obtainToken(restTemplate, CUSTOMER_A_USERNAME, CUSTOMER_A_PASSWORD);
	}

	public static String customerBToken(TestRestTemplate restTemplate) {
		return obtainToken(restTemplate, CUSTOMER_B_USERNAME, CUSTOMER_B_PASSWORD);
	}

	public static String operationsToken(TestRestTemplate restTemplate) {
		return obtainToken(restTemplate, OPERATIONS_USERNAME, OPERATIONS_PASSWORD);
	}

	public static HttpHeaders bearerHeaders(TestRestTemplate restTemplate, String username, String password) {
		HttpHeaders headers = new HttpHeaders();
		headers.setBearerAuth(obtainToken(restTemplate, username, password));
		return headers;
	}

	/**
	 * Adds a bearer Authorization header to an existing header set (e.g.
	 * one that already sets Content-Type or Idempotency-Key) without
	 * discarding it.
	 */
	public static HttpHeaders withBearerAuth(HttpHeaders headers, TestRestTemplate restTemplate, String username,
			String password) {
		headers.setBearerAuth(obtainToken(restTemplate, username, password));
		return headers;
	}

	/**
	 * Crafts a validly-shaped, self-signed JWT for negative test scenarios
	 * (wrong signature, wrong issuer/audience, expired) -- never used to
	 * simulate a legitimate token, only to prove the server-side validator
	 * rejects each specific defect.
	 */
	public static String craftToken(String signingKeyBase64, String issuer, String audience, String subject,
			String role, Instant issuedAt, Instant expiresAt) {
		try {
			byte[] keyBytes = Base64.getDecoder().decode(signingKeyBase64);
			JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
					.subject(subject)
					.issuer(issuer)
					.issueTime(Date.from(issuedAt))
					.expirationTime(Date.from(expiresAt));
			if (audience != null) {
				claims.audience(audience);
			}
			if (role != null) {
				claims.claim("roles", List.of(role));
			}
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
			jwt.sign(new MACSigner(keyBytes));
			return jwt.serialize();
		} catch (Exception e) {
			throw new IllegalStateException("Failed to craft test JWT", e);
		}
	}

	@SuppressWarnings("unchecked")
	public static String obtainToken(TestRestTemplate restTemplate, String username, String password) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		HttpEntity<Map<String, String>> request =
				new HttpEntity<>(Map.of("username", username, "password", password), headers);
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/auth/token", request, Map.class);
		if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
			throw new IllegalStateException(
					"Failed to obtain test auth token for '" + username + "': " + response.getStatusCode());
		}
		return (String) response.getBody().get("accessToken");
	}

}
