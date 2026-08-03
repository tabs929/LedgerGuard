package com.tarun.ledgerguard.reconciliation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises settlement reconciliation (Task 15) at the HTTP boundary and
 * verifies persisted database state directly via JDBC, against a real,
 * isolated PostgreSQL 16.4 Testcontainer that runs every migration from
 * scratch. No mocks are used for matching, persistence, or concurrency —
 * concurrency is exercised against real PostgreSQL constraint arbitration.
 *
 * <p>{@code ledgerguard.outbox.publisher.enabled}/
 * {@code ledgerguard.inbox.consumer.enabled} are already {@code false} by
 * default under the {@code test} profile — this class never attempts a
 * Kafka connection.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReconciliationIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	TestRestTemplate restTemplate;

	@Autowired
	DataSource dataSource;

	private static final String HEADER = "external_reference,transaction_id,amount,currency,settled_at";

	// ------------------------------------------------------------------
	// HTTP helpers
	// ------------------------------------------------------------------

	private UUID createUsdCustomerAccount(String owner) {
		Map<String, Object> body = Map.of("ownerName", owner, "currency", "USD");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map> response = restTemplate.postForEntity("/api/v1/accounts", new HttpEntity<>(body, headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return UUID.fromString((String) response.getBody().get("id"));
	}

	private UUID depositAndGetTransactionId(UUID accountId, String amount) {
		Map<String, Object> body = Map.of("amount", amount, "currency", "USD");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Idempotency-Key", UUID.randomUUID().toString());
		ResponseEntity<Map> response = restTemplate.postForEntity(
				"/api/v1/accounts/" + accountId + "/deposits", new HttpEntity<>(body, headers), Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return UUID.fromString((String) response.getBody().get("transactionId"));
	}

	private String row(String ref, UUID transactionId, String amount, String currency, String settledAt) {
		return ref + "," + transactionId + "," + amount + "," + currency + "," + settledAt;
	}

	private String csv(String... rows) {
		StringBuilder sb = new StringBuilder(HEADER).append('\n');
		for (String r : rows) {
			sb.append(r).append('\n');
		}
		return sb.toString();
	}

	private ResponseEntity<Map> postSettlementImport(String source, String csvContent) {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("source", source);
		body.add("file", new ByteArrayResource(csvContent.getBytes(StandardCharsets.UTF_8)) {
			@Override
			public String getFilename() {
				return "settlement.csv";
			}
		});
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		return restTemplate.postForEntity("/api/v1/settlement-imports", new HttpEntity<>(body, headers), Map.class);
	}

	private UUID importAndGetId(String source, String csvContent) {
		ResponseEntity<Map> response = postSettlementImport(source, csvContent);
		assertThat(response.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.OK);
		return UUID.fromString((String) response.getBody().get("importId"));
	}

	private ResponseEntity<Map> postReconcile(UUID importId) {
		return restTemplate.postForEntity("/api/v1/settlement-imports/" + importId + "/reconciliation", null, Map.class);
	}

	private ResponseEntity<Map> getRun(UUID importId) {
		return restTemplate.getForEntity("/api/v1/settlement-imports/" + importId + "/reconciliation", Map.class);
	}

	private ResponseEntity<Map> getResults(UUID importId, int page, int size) {
		return restTemplate.getForEntity(
				"/api/v1/settlement-imports/" + importId + "/reconciliation/results?page=" + page + "&size=" + size,
				Map.class);
	}

	private String uniqueSource() {
		return "bank-" + UUID.randomUUID();
	}

	// ------------------------------------------------------------------
	// matching
	// ------------------------------------------------------------------

	@Test
	void matchedDepositIsClassifiedMatched() {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner 1");
		UUID txnId = depositAndGetTransactionId(accountId, "100.00");
		UUID importId = importAndGetId(uniqueSource(), csv(row("EXT-1", txnId, "100.00", "USD", "2026-08-03T10:00:00Z")));

		ResponseEntity<Map> response = postReconcile(importId);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().get("matchedCount")).isEqualTo(1);
		assertThat(response.getBody().get("discrepancyCount")).isEqualTo(0);
		assertThat(response.getBody().get("inconsistentCount")).isEqualTo(0);
		assertThat(response.getBody().get("reconciliationResultCount")).isEqualTo(1);
		assertThat(response.getBody().get("importedFileRows")).isEqualTo(1);
		assertThat(response.getBody().get("newlyRecordedObservations")).isEqualTo(1);
		assertThat(response.getBody().get("duplicateRows")).isEqualTo(0);
		assertThat(response.getBody().get("algorithmVersion")).isEqualTo(1);
		assertThat(response.getBody().get("replayed")).isEqualTo(false);

		ResponseEntity<Map> results = getResults(importId, 0, 20);
		List<Map> content = (List<Map>) results.getBody().get("content");
		assertThat(content).hasSize(1);
		assertThat(content.get(0).get("outcome")).isEqualTo("MATCHED");
		// Fetched as a generic Map, so Jackson coerces the JSON number to
		// a Double -- construct a BigDecimal from its string form for an
		// exact-value comparison instead of trusting the Double's own
		// textual representation.
		assertThat(new BigDecimal(String.valueOf(content.get(0).get("internalAmount")))).isEqualByComparingTo("100.00");
		assertThat(content.get(0).get("internalCurrency")).isEqualTo("USD");
	}

	@Test
	void transferIsIneligible() throws SQLException {
		UUID sourceAccount = createUsdCustomerAccount("Reconciliation Transfer Source");
		UUID destinationAccount = createUsdCustomerAccount("Reconciliation Transfer Destination");
		depositAndGetTransactionId(sourceAccount, "500.00");

		Map<String, Object> transferBody = Map.of(
				"sourceAccountId", sourceAccount.toString(),
				"destinationAccountId", destinationAccount.toString(),
				"amount", "50.00", "currency", "USD");
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Idempotency-Key", UUID.randomUUID().toString());
		ResponseEntity<Map> transferResponse = restTemplate.postForEntity(
				"/api/v1/transfers", new HttpEntity<>(transferBody, headers), Map.class);
		UUID transferTxnId = UUID.fromString((String) transferResponse.getBody().get("transactionId"));

		UUID importId = importAndGetId(uniqueSource(),
				csv(row("EXT-TRANSFER", transferTxnId, "50.00", "USD", "2026-08-03T10:00:00Z")));
		ResponseEntity<Map> response = postReconcile(importId);

		assertThat(response.getBody().get("discrepancyCount")).isEqualTo(1);
		ResponseEntity<Map> results = getResults(importId, 0, 20);
		List<Map> content = (List<Map>) results.getBody().get("content");
		assertThat(content.get(0).get("outcome")).isEqualTo("INELIGIBLE_TRANSACTION_TYPE");
		assertThat(content.get(0).get("internalAmount")).isNull();
	}

	@Test
	void unknownInternalTransactionIsClassifiedNotFound() {
		UUID importId = importAndGetId(uniqueSource(),
				csv(row("EXT-UNKNOWN", UUID.randomUUID(), "10.00", "USD", "2026-08-03T10:00:00Z")));
		ResponseEntity<Map> response = postReconcile(importId);

		assertThat(response.getBody().get("discrepancyCount")).isEqualTo(1);
		ResponseEntity<Map> results = getResults(importId, 0, 20);
		List<Map> content = (List<Map>) results.getBody().get("content");
		assertThat(content.get(0).get("outcome")).isEqualTo("INTERNAL_TRANSACTION_NOT_FOUND");
	}

	@Test
	void exactAmountMismatchIsDetected() {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner Amount Mismatch");
		UUID txnId = depositAndGetTransactionId(accountId, "100.00");
		UUID importId = importAndGetId(uniqueSource(), csv(row("EXT-AMT", txnId, "50.00", "USD", "2026-08-03T10:00:00Z")));

		ResponseEntity<Map> response = postReconcile(importId);
		ResponseEntity<Map> results = getResults(importId, 0, 20);
		List<Map> content = (List<Map>) results.getBody().get("content");
		assertThat(content.get(0).get("outcome")).isEqualTo("AMOUNT_MISMATCH");
	}

	@Test
	void exactCurrencyMismatchIsDetected() throws SQLException {
		// Only USD is supported by the settlement CSV parser, so the
		// "internal" side is deliberately planted with a non-USD currency
		// directly via JDBC (bypassing DepositService entirely) to
		// exercise the real currency-mismatch comparison -- the same
		// technique Task 14's own currency-conflict test uses.
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner Currency Mismatch");
		UUID fundingAccountId = fetchUsdFundingAccountId();
		UUID txnId = insertRawDeposit(fundingAccountId, accountId, "100.0000", "EUR");

		UUID importId = importAndGetId(uniqueSource(), csv(row("EXT-CCY", txnId, "100.00", "USD", "2026-08-03T10:00:00Z")));
		postReconcile(importId);
		ResponseEntity<Map> results = getResults(importId, 0, 20);
		List<Map> content = (List<Map>) results.getBody().get("content");
		assertThat(content.get(0).get("outcome")).isEqualTo("CURRENCY_MISMATCH");
	}

	@Test
	void combinedAmountAndCurrencyMismatchIsDetected() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner Combined Mismatch");
		UUID fundingAccountId = fetchUsdFundingAccountId();
		UUID txnId = insertRawDeposit(fundingAccountId, accountId, "100.0000", "EUR");

		UUID importId = importAndGetId(uniqueSource(), csv(row("EXT-COMBO", txnId, "50.00", "USD", "2026-08-03T10:00:00Z")));
		postReconcile(importId);
		ResponseEntity<Map> results = getResults(importId, 0, 20);
		List<Map> content = (List<Map>) results.getBody().get("content");
		assertThat(content.get(0).get("outcome")).isEqualTo("AMOUNT_AND_CURRENCY_MISMATCH");
	}

	@Test
	void multipleObservationsInOneImportAreClassifiedIndependently() {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner Multi");
		UUID matchedTxn = depositAndGetTransactionId(accountId, "10.00");
		UUID mismatchTxn = depositAndGetTransactionId(accountId, "20.00");

		UUID importId = importAndGetId(uniqueSource(), csv(
				row("EXT-M1", matchedTxn, "10.00", "USD", "2026-08-03T10:00:00Z"),
				row("EXT-M2", mismatchTxn, "999.00", "USD", "2026-08-03T10:00:00Z"),
				row("EXT-M3", UUID.randomUUID(), "5.00", "USD", "2026-08-03T10:00:00Z")));

		ResponseEntity<Map> response = postReconcile(importId);
		assertThat(response.getBody().get("reconciliationResultCount")).isEqualTo(3);
		assertThat(response.getBody().get("matchedCount")).isEqualTo(1);
		assertThat(response.getBody().get("discrepancyCount")).isEqualTo(2);
	}

	@Test
	void sameTransactionReportedByMultipleSourcesReconcilesIndependently() {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner Multi Source");
		UUID txnId = depositAndGetTransactionId(accountId, "75.00");

		UUID importA = importAndGetId(uniqueSource(), csv(row("EXT-A", txnId, "75.00", "USD", "2026-08-03T10:00:00Z")));
		UUID importB = importAndGetId(uniqueSource(), csv(row("EXT-B", txnId, "75.00", "USD", "2026-08-03T10:00:00Z")));

		ResponseEntity<Map> responseA = postReconcile(importA);
		ResponseEntity<Map> responseB = postReconcile(importB);

		assertThat(responseA.getBody().get("matchedCount")).isEqualTo(1);
		assertThat(responseB.getBody().get("matchedCount")).isEqualTo(1);
		assertThat(responseA.getBody().get("runId")).isNotEqualTo(responseB.getBody().get("runId"));
	}

	@Test
	void malformedInternalLedgerStructureIsInconsistent() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner Malformed");
		UUID fundingAccountId = fetchUsdFundingAccountId();
		// Only one entry -- a valid deposit always has exactly two.
		UUID txnId = insertRawSingleEntryDeposit(fundingAccountId, accountId, "100.0000", "USD");

		UUID importId = importAndGetId(uniqueSource(), csv(row("EXT-BAD", txnId, "100.00", "USD", "2026-08-03T10:00:00Z")));

		// The approved design requires INTERNAL_LEDGER_INCONSISTENT to be
		// ordinary reconciliation result data in a successful 2xx command
		// response -- never an HTTP/server error. Assert the command's own
		// status explicitly, not just the result content.
		ResponseEntity<Map> response = postReconcile(importId);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().get("inconsistentCount")).isEqualTo(1);
		assertThat(response.getBody().get("discrepancyCount")).isEqualTo(0);
		assertThat(response.getBody().get("matchedCount")).isEqualTo(0);

		ResponseEntity<Map> results = getResults(importId, 0, 20);
		assertThat(results.getStatusCode()).isEqualTo(HttpStatus.OK);
		List<Map> content = (List<Map>) results.getBody().get("content");
		assertThat(content.get(0).get("outcome")).isEqualTo("INTERNAL_LEDGER_INCONSISTENT");
		assertThat(content.get(0).get("internalAmount")).isNull();

		// A repeat of the same (already-committed) run also stays 2xx.
		ResponseEntity<Map> replay = postReconcile(importId);
		assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(replay.getBody().get("inconsistentCount")).isEqualTo(1);
	}

	@Test
	void noAccountBalanceComparisonOccurs() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner Balance Independence");
		UUID txnId = depositAndGetTransactionId(accountId, "100.00");
		// Corrupt the materialized balance directly -- if reconciliation
		// ever consulted account.balance, this would change its result.
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("UPDATE account SET balance = 0 WHERE id = ?")) {
			statement.setObject(1, accountId);
			statement.executeUpdate();
		}

		UUID importId = importAndGetId(uniqueSource(), csv(row("EXT-BAL", txnId, "100.00", "USD", "2026-08-03T10:00:00Z")));
		ResponseEntity<Map> response = postReconcile(importId);
		assertThat(response.getBody().get("matchedCount")).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// duplicate / first_import_id scoping
	// ------------------------------------------------------------------

	@Test
	void allDuplicateImportProducesAValidZeroResultRun() {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner All Duplicate");
		UUID txnId = depositAndGetTransactionId(accountId, "10.00");
		String source = uniqueSource();
		String row = row("EXT-DUP", txnId, "10.00", "USD", "2026-08-03T10:00:00Z");

		importAndGetId(source, csv(row));
		// A byte-distinct file containing the identical row -- Task 14
		// classifies it as a pure duplicate, no new settlement_record.
		ResponseEntity<Map> secondImport = postSettlementImport(source, csv(row) + "\n");
		UUID importB = UUID.fromString((String) secondImport.getBody().get("importId"));
		assertThat(secondImport.getBody().get("insertedRows")).isEqualTo(0);
		assertThat(secondImport.getBody().get("duplicateRows")).isEqualTo(1);

		ResponseEntity<Map> response = postReconcile(importB);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().get("reconciliationResultCount")).isEqualTo(0);
		assertThat(response.getBody().get("matchedCount")).isEqualTo(0);
		assertThat(response.getBody().get("discrepancyCount")).isEqualTo(0);
		assertThat(response.getBody().get("inconsistentCount")).isEqualTo(0);
		assertThat(response.getBody().get("importedFileRows")).isEqualTo(1);
		assertThat(response.getBody().get("duplicateRows")).isEqualTo(1);
	}

	@Test
	void importedFileRowsCanExceedReconciliationResultCount() {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner Exceeding");
		UUID txnId = depositAndGetTransactionId(accountId, "10.00");
		String source = uniqueSource();
		String duplicateRow = row("EXT-EXC-DUP", txnId, "10.00", "USD", "2026-08-03T10:00:00Z");

		importAndGetId(source, csv(duplicateRow));

		UUID newTxnId = depositAndGetTransactionId(accountId, "20.00");
		String newRow = row("EXT-EXC-NEW", newTxnId, "20.00", "USD", "2026-08-03T10:00:00Z");
		ResponseEntity<Map> secondImport = postSettlementImport(source, csv(duplicateRow, newRow));
		UUID importB = UUID.fromString((String) secondImport.getBody().get("importId"));

		ResponseEntity<Map> response = postReconcile(importB);
		int importedFileRows = (int) response.getBody().get("importedFileRows");
		int reconciliationResultCount = (int) response.getBody().get("reconciliationResultCount");
		assertThat(importedFileRows).isEqualTo(2);
		assertThat(reconciliationResultCount).isEqualTo(1);
		assertThat(importedFileRows).isGreaterThan(reconciliationResultCount);
	}

	@Test
	void duplicateObservationsAreReconciledOnlyUnderFirstImportId() {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner First Import Scoping");
		UUID txnId = depositAndGetTransactionId(accountId, "10.00");
		String source = uniqueSource();
		String sharedRow = row("EXT-SCOPE", txnId, "10.00", "USD", "2026-08-03T10:00:00Z");

		UUID importA = importAndGetId(source, csv(sharedRow));
		ResponseEntity<Map> importBResponse = postSettlementImport(source, csv(sharedRow) + "\n");
		UUID importB = UUID.fromString((String) importBResponse.getBody().get("importId"));

		ResponseEntity<Map> runA = postReconcile(importA);
		ResponseEntity<Map> runB = postReconcile(importB);

		assertThat(runA.getBody().get("reconciliationResultCount")).isEqualTo(1);
		assertThat(runB.getBody().get("reconciliationResultCount")).isEqualTo(0);
	}

	// ------------------------------------------------------------------
	// replay / algorithm version
	// ------------------------------------------------------------------

	@Test
	void repeatingTheSameImportAndAlgorithmVersionReplays() {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner Replay");
		UUID txnId = depositAndGetTransactionId(accountId, "10.00");
		UUID importId = importAndGetId(uniqueSource(), csv(row("EXT-REPLAY", txnId, "10.00", "USD", "2026-08-03T10:00:00Z")));

		ResponseEntity<Map> first = postReconcile(importId);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		ResponseEntity<Map> second = postReconcile(importId);
		assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(second.getBody().get("replayed")).isEqualTo(true);
		assertThat(second.getBody().get("runId")).isEqualTo(first.getBody().get("runId"));
		assertThat(second.getBody().get("createdAt")).isEqualTo(first.getBody().get("createdAt"));
	}

	@Test
	void getRunReturnsTheCommittedRunAfterReconciling() {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner Get Run");
		UUID txnId = depositAndGetTransactionId(accountId, "10.00");
		UUID importId = importAndGetId(uniqueSource(), csv(row("EXT-GET", txnId, "10.00", "USD", "2026-08-03T10:00:00Z")));

		ResponseEntity<Map> posted = postReconcile(importId);
		ResponseEntity<Map> fetched = getRun(importId);

		assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(fetched.getBody().get("runId")).isEqualTo(posted.getBody().get("runId"));
	}

	// ------------------------------------------------------------------
	// concurrency
	// ------------------------------------------------------------------

	@Test
	void concurrentSameVersionCommandsProduceExactlyOneRun() throws Exception {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner Concurrency");
		UUID txnId = depositAndGetTransactionId(accountId, "10.00");
		UUID importId = importAndGetId(uniqueSource(), csv(row("EXT-CONC", txnId, "10.00", "USD", "2026-08-03T10:00:00Z")));

		int parallelism = 4;
		ExecutorService executor = Executors.newFixedThreadPool(parallelism);
		CountDownLatch ready = new CountDownLatch(parallelism);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Callable<ResponseEntity<Map>>> tasks = new ArrayList<>();
			for (int i = 0; i < parallelism; i++) {
				tasks.add(() -> {
					ready.countDown();
					start.await(10, TimeUnit.SECONDS);
					return postReconcile(importId);
				});
			}
			List<Future<ResponseEntity<Map>>> futures = executor.invokeAll(tasks);
			ready.await(10, TimeUnit.SECONDS);
			start.countDown();

			List<ResponseEntity<Map>> results = new ArrayList<>();
			for (Future<ResponseEntity<Map>> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}

			assertThat(results).allMatch(r -> r.getStatusCode() == HttpStatus.CREATED || r.getStatusCode() == HttpStatus.OK);
			assertThat(results).filteredOn(r -> r.getStatusCode() == HttpStatus.CREATED).hasSize(1);
			// The losers successfully loaded the committed winner -- same
			// runId, not an error.
			Object winningRunId = results.get(0).getBody().get("runId");
			assertThat(results).allSatisfy(r -> assertThat(r.getBody().get("runId")).isEqualTo(winningRunId));

			assertThat(countRunsForImport(importId)).isEqualTo(1);
		} finally {
			executor.shutdown();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	// ------------------------------------------------------------------
	// transaction / rollback behavior
	// ------------------------------------------------------------------

	@Test
	void forcedResultInsertionFailureRollsBackTheWholeRunAndSucceedsAfterCorrection() throws Exception {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner Forced Failure");
		UUID txnId = depositAndGetTransactionId(accountId, "10.00");
		UUID importId = importAndGetId(uniqueSource(), csv(row("EXT-FORCE", txnId, "10.00", "USD", "2026-08-03T10:00:00Z")));

		long runsBefore = countRows("reconciliation_run");
		long resultsBefore = countRows("reconciliation_result");

		// A genuine PostgreSQL-level failure, deterministic and independent
		// of classification content: block every new reconciliation_result
		// insert with a real CHECK constraint (NOT VALID so it doesn't
		// retroactively reject rows already committed by earlier tests
		// sharing this container). ReconciliationProcessor claims the
		// owning reconciliation_run row FIRST, then inserts results in the
		// same transaction -- so this forces the failure to happen only
		// after the run row already exists (uncommitted), proving the run
		// itself is rolled back too, not just the results.
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE reconciliation_result ADD CONSTRAINT chk_test_block_insert CHECK (1 = 0) NOT VALID");
		}

		try {
			ResponseEntity<Map> response = postReconcile(importId);
			assertThat(response.getStatusCode().is5xxServerError()).isTrue();

			// No partial run: neither the run row nor any result row
			// persisted.
			assertThat(countRows("reconciliation_run")).isEqualTo(runsBefore);
			assertThat(countRows("reconciliation_result")).isEqualTo(resultsBefore);
		} finally {
			try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
				statement.execute("ALTER TABLE reconciliation_result DROP CONSTRAINT chk_test_block_insert");
			}
		}

		// Retry after removing the test-only failure mechanism succeeds
		// normally, as a fresh, non-replayed run.
		ResponseEntity<Map> retried = postReconcile(importId);
		assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(retried.getBody().get("replayed")).isEqualTo(false);
		assertThat(retried.getBody().get("matchedCount")).isEqualTo(1);
		assertThat(countRows("reconciliation_run")).isEqualTo(runsBefore + 1);
		assertThat(countRows("reconciliation_result")).isEqualTo(resultsBefore + 1);
	}

	// ------------------------------------------------------------------
	// financial non-effects
	// ------------------------------------------------------------------

	@Test
	void reconciliationNeverMutatesAnyFinancialOrEventState() throws SQLException {
		UUID accountId = createUsdCustomerAccount("Reconciliation Owner Non Effects");
		UUID txnId = depositAndGetTransactionId(accountId, "10.00");
		UUID importId = importAndGetId(uniqueSource(), csv(row("EXT-NONEFFECT", txnId, "10.00", "USD", "2026-08-03T10:00:00Z")));

		long accountsBefore = countRows("account");
		BigDecimal balancesBefore = sumBalances();
		long transactionsBefore = countRows("ledger_transaction");
		long entriesBefore = countRows("ledger_entry");
		long idempotencyBefore = countRows("idempotency_key");
		long settlementImportBefore = countRows("settlement_import");
		long settlementRecordBefore = countRows("settlement_record");
		long outboxBefore = countRows("outbox_event");
		long processedEventBefore = countRows("processed_event");

		ResponseEntity<Map> response = postReconcile(importId);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		assertThat(countRows("account")).isEqualTo(accountsBefore);
		assertThat(sumBalances()).isEqualByComparingTo(balancesBefore);
		assertThat(countRows("ledger_transaction")).isEqualTo(transactionsBefore);
		assertThat(countRows("ledger_entry")).isEqualTo(entriesBefore);
		assertThat(countRows("idempotency_key")).isEqualTo(idempotencyBefore);
		assertThat(countRows("settlement_import")).isEqualTo(settlementImportBefore);
		assertThat(countRows("settlement_record")).isEqualTo(settlementRecordBefore);
		assertThat(countRows("outbox_event")).isEqualTo(outboxBefore);
		assertThat(countRows("processed_event")).isEqualTo(processedEventBefore);
	}

	// ------------------------------------------------------------------
	// request-level validation
	// ------------------------------------------------------------------

	@Test
	void nonexistentImportReturns404OnCommand() {
		ResponseEntity<Map> response = postReconcile(UUID.randomUUID());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void nonexistentImportReturns404OnGetRun() {
		ResponseEntity<Map> response = getRun(UUID.randomUUID());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void existingImportNeverReconciledReturns404OnGetRun() {
		UUID importId = importAndGetId(uniqueSource(),
				csv(row("EXT-NEVER", UUID.randomUUID(), "1.00", "USD", "2026-08-03T10:00:00Z")));
		ResponseEntity<Map> response = getRun(importId);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void malformedImportIdReturns400() {
		ResponseEntity<Map> response = restTemplate.exchange(
				"/api/v1/settlement-imports/not-a-uuid/reconciliation", HttpMethod.POST, null, Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void excessivePageSizeReturns400() {
		UUID importId = importAndGetId(uniqueSource(),
				csv(row("EXT-PAGE", UUID.randomUUID(), "1.00", "USD", "2026-08-03T10:00:00Z")));
		postReconcile(importId);
		ResponseEntity<Map> response = getResults(importId, 0, 101);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	// ------------------------------------------------------------------
	// helpers: raw JDBC ledger manipulation (bypassing DepositService)
	// ------------------------------------------------------------------

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

	private UUID insertRawDeposit(UUID fundingAccountId, UUID customerAccountId, String amount, String currency)
			throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			UUID transactionId = insertLedgerTransaction(connection);
			insertLedgerEntry(connection, transactionId, fundingAccountId, "DEBIT", amount, currency);
			insertLedgerEntry(connection, transactionId, customerAccountId, "CREDIT", amount, currency);
			return transactionId;
		}
	}

	private UUID insertRawSingleEntryDeposit(UUID fundingAccountId, UUID customerAccountId, String amount, String currency)
			throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			UUID transactionId = insertLedgerTransaction(connection);
			insertLedgerEntry(connection, transactionId, fundingAccountId, "DEBIT", amount, currency);
			return transactionId;
		}
	}

	private UUID insertLedgerTransaction(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO ledger_transaction (transaction_type, status) VALUES ('DEPOSIT', 'COMPLETED') RETURNING id")) {
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return (UUID) resultSet.getObject("id");
			}
		}
	}

	private void insertLedgerEntry(Connection connection, UUID transactionId, UUID accountId, String entryType,
			String amount, String currency) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO ledger_entry (transaction_id, account_id, entry_type, amount, currency) "
						+ "VALUES (?, ?, ?, ?, ?)")) {
			statement.setObject(1, transactionId);
			statement.setObject(2, accountId);
			statement.setString(3, entryType);
			statement.setBigDecimal(4, new BigDecimal(amount));
			statement.setString(5, currency);
			statement.executeUpdate();
		}
	}

	private long countRunsForImport(UUID importId) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM reconciliation_run WHERE settlement_import_id = ?")) {
			statement.setObject(1, importId);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getLong(1);
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

	private BigDecimal sumBalances() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("SELECT SUM(balance) FROM account")) {
			resultSet.next();
			BigDecimal sum = resultSet.getBigDecimal(1);
			return sum == null ? BigDecimal.ZERO : sum;
		}
	}

}
