package com.tarun.ledgerguard.settlement;

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

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Exercises settlement CSV import (Task 14) at the HTTP boundary and
 * verifies persisted database state directly via JDBC, against a real,
 * isolated PostgreSQL 16.4 Testcontainer that runs every migration from
 * scratch. No mocks are used for persistence or duplicate/conflict
 * classification -- concurrency is exercised against real PostgreSQL
 * constraint arbitration, not Java-only synchronization.
 *
 * <p>{@code ledgerguard.outbox.publisher.enabled} and
 * {@code ledgerguard.inbox.consumer.enabled} are already {@code false} by
 * default under the {@code test} profile (application-test.yml, Task
 * 12/13) -- this class never attempts a Kafka connection.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SettlementImportIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	TestRestTemplate restTemplate;

	@Autowired
	DataSource dataSource;

	private static final String HEADER = "external_reference,transaction_id,amount,currency,settled_at";

	private String uniqueSource() {
		return "bank-" + UUID.randomUUID();
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

	private ResponseEntity<Map> postImport(String source, byte[] csvBytes) {
		return postImport(source, csvBytes, "settlement.csv", null);
	}

	private ResponseEntity<Map> postImport(String source, byte[] csvBytes, String filename, MediaType filePartContentType) {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		if (source != null) {
			body.add("source", source);
		}
		if (csvBytes != null) {
			ByteArrayResource fileResource = new ByteArrayResource(csvBytes) {
				@Override
				public String getFilename() {
					return filename;
				}
			};
			if (filePartContentType != null) {
				HttpHeaders filePartHeaders = new HttpHeaders();
				filePartHeaders.setContentType(filePartContentType);
				body.add("file", new HttpEntity<>(fileResource, filePartHeaders));
			} else {
				body.add("file", fileResource);
			}
		}
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.MULTIPART_FORM_DATA);
		return restTemplate.postForEntity("/api/v1/settlement-imports", new HttpEntity<>(body, headers), Map.class);
	}

	// ------------------------------------------------------------------
	// valid imports
	// ------------------------------------------------------------------

	@Test
	void singleRowCsvReturns201() {
		String source = uniqueSource();
		byte[] content = csv(row("EXT-001", UUID.randomUUID(), "100.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);

		ResponseEntity<Map> response = postImport(source, content);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().get("totalRows")).isEqualTo(1);
		assertThat(response.getBody().get("insertedRows")).isEqualTo(1);
		assertThat(response.getBody().get("duplicateRows")).isEqualTo(0);
		assertThat(response.getBody().get("replayed")).isEqualTo(false);
		assertThat(response.getBody().get("importId")).isNotNull();
	}

	@Test
	void multiRowCsvReturns201WithCorrectCounts() {
		String source = uniqueSource();
		byte[] content = csv(
				row("EXT-101", UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"),
				row("EXT-102", UUID.randomUUID(), "20.00", "USD", "2026-07-15T10:00:00Z"),
				row("EXT-103", UUID.randomUUID(), "30.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);

		ResponseEntity<Map> response = postImport(source, content);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(response.getBody().get("totalRows")).isEqualTo(3);
		assertThat(response.getBody().get("insertedRows")).isEqualTo(3);
	}

	@Test
	void persistedValuesMatchExactlyAndFileHashUsesExactOriginalBytes() throws SQLException {
		String source = uniqueSource();
		UUID transactionId = UUID.randomUUID();
		byte[] content = csv(row("EXT-201", transactionId, "55.25", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		String expectedFileHash = Sha256.hex(content);

		ResponseEntity<Map> response = postImport(source, content);
		assertThat(response.getBody().get("fileHash")).isEqualTo(expectedFileHash);

		UUID importId = UUID.fromString((String) response.getBody().get("importId"));
		Map<String, Object> record = fetchRecordByExternalReference(SourceNormalizer.normalize(source), "EXT-201");
		assertThat(record.get("transaction_id")).isEqualTo(transactionId);
		assertThat(((java.math.BigDecimal) record.get("amount"))).isEqualByComparingTo("55.25");
		assertThat(record.get("currency")).isEqualTo("USD");
		assertThat(record.get("source_row_number")).isEqualTo(1);
		assertThat(record.get("first_import_id")).isEqualTo(importId);
	}

	@Test
	void rowHashUsesTheApprovedCanonicalRepresentation() throws SQLException {
		String source = uniqueSource();
		UUID transactionId = UUID.randomUUID();
		byte[] content = csv(row("EXT-301", transactionId, "10.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		postImport(source, content);

		String expectedRowHash = RowFingerprint.sha256Hex(SourceNormalizer.normalize(source), "EXT-301", transactionId,
				new java.math.BigDecimal("10.00"), "USD", java.time.Instant.parse("2026-07-15T10:00:00Z"));

		Map<String, Object> record = fetchRecordByExternalReference(SourceNormalizer.normalize(source), "EXT-301");
		assertThat(record.get("row_hash")).isEqualTo(expectedRowHash);
	}

	@Test
	void unknownTransactionUuidIsAcceptedNotRejected() {
		String source = uniqueSource();
		byte[] content = csv(row("EXT-401", UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		ResponseEntity<Map> response = postImport(source, content);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	@Test
	void aRealDepositTransactionUuidMayBeObservedWithoutComparisonToItsActualAmount() throws SQLException {
		UUID depositTransactionId = createDepositTransactionId(new java.math.BigDecimal("100.0000"));
		String source = uniqueSource();
		// Reported amount deliberately differs from the real ledger
		// transaction's amount -- Task 14 never compares against it.
		byte[] content = csv(row("EXT-501", depositTransactionId, "999.99", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);

		ResponseEntity<Map> response = postImport(source, content);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	// ------------------------------------------------------------------
	// duplicate behavior
	// ------------------------------------------------------------------

	@Test
	void exactFileReplayReturns200AndCreatesNoNewRows() throws SQLException {
		String source = uniqueSource();
		byte[] content = csv(row("EXT-601", UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);

		ResponseEntity<Map> first = postImport(source, content);
		assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		long importsBefore = countRows("settlement_import");
		long recordsBefore = countRows("settlement_record");

		ResponseEntity<Map> replay = postImport(source, content);
		assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(replay.getBody().get("replayed")).isEqualTo(true);
		assertThat(replay.getBody().get("importId")).isEqualTo(first.getBody().get("importId"));

		assertThat(countRows("settlement_import")).isEqualTo(importsBefore);
		assertThat(countRows("settlement_record")).isEqualTo(recordsBefore);
	}

	@Test
	void logicallyIdenticalRowInAByteDistinctFileIsARowDuplicate() {
		String source = uniqueSource();
		UUID transactionId = UUID.randomUUID();
		byte[] first = csv(row("EXT-701", transactionId, "10.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		// Byte-distinct (extra row) file containing one identical row plus
		// one new row.
		byte[] second = csv(
				row("EXT-701", transactionId, "10.00", "USD", "2026-07-15T10:00:00Z"),
				row("EXT-702", UUID.randomUUID(), "20.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);

		ResponseEntity<Map> firstResponse = postImport(source, first);
		assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		ResponseEntity<Map> secondResponse = postImport(source, second);
		assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(secondResponse.getBody().get("totalRows")).isEqualTo(2);
		assertThat(secondResponse.getBody().get("insertedRows")).isEqualTo(1);
		assertThat(secondResponse.getBody().get("duplicateRows")).isEqualTo(1);
	}

	@Test
	void conflictingAmountReturns409AndRollsBackTheEntireImport() throws SQLException {
		assertConflictRollsBackWholeImport(
				row("EXT-801", UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"),
				txnId -> row("EXT-801", txnId, "99.00", "USD", "2026-07-15T10:00:00Z"));
	}

	@Test
	void conflictingCurrencyReturns409() throws SQLException {
		// LedgerGuard supports only USD in Phase 1 (AccountService,
		// mirrored in SettlementCsvParser), so two DIFFERENT supported
		// currencies cannot both appear in valid CSV input today -- there
		// is exactly one. To still exercise the real currency-mismatch
		// branch of the conflict-classification logic (not just the CSV
		// parser's currency-support check), the "existing" observation is
		// planted directly at the database level with a non-USD currency,
		// simulating data that predates a future multi-currency Phase.
		// The conflict detection itself (StoredSettlementRecord vs. the
		// new row's row_hash/fields) runs unchanged either way.
		String source = uniqueSource();
		String normalizedSource = SourceNormalizer.normalize(source);
		UUID importId = plantSettlementImport(normalizedSource);
		plantSettlementRecord(normalizedSource, "EXT-802", UUID.randomUUID(), "10.00", "EUR",
				"2026-07-15T10:00:00Z", importId, 1);

		long importsBefore = countRows("settlement_import");
		long recordsBefore = countRows("settlement_record");

		byte[] conflictingFile = csv(row("EXT-802", UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		ResponseEntity<Map> response = postImport(source, conflictingFile);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(countRows("settlement_import")).isEqualTo(importsBefore);
		assertThat(countRows("settlement_record")).isEqualTo(recordsBefore);
	}

	private UUID plantSettlementImport(String normalizedSource) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO settlement_import (source, normalized_source, file_hash, file_size_bytes, "
								+ "total_row_count, inserted_row_count, duplicate_row_count) "
								+ "VALUES (?, ?, ?, 10, 1, 1, 0) RETURNING id")) {
			statement.setString(1, normalizedSource);
			statement.setString(2, normalizedSource);
			statement.setString(3, UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return (UUID) resultSet.getObject("id");
			}
		}
	}

	private void plantSettlementRecord(String normalizedSource, String externalReference, UUID transactionId,
			String amount, String currency, String settledAt, UUID importId, int sourceRowNumber) throws SQLException {
		String rowHash = RowFingerprint.sha256Hex(normalizedSource, externalReference, transactionId,
				new java.math.BigDecimal(amount), currency, java.time.Instant.parse(settledAt));
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO settlement_record (normalized_source, external_reference, transaction_id, "
								+ "amount, currency, settled_at, row_hash, first_import_id, source_row_number) "
								+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
			statement.setString(1, normalizedSource);
			statement.setString(2, externalReference);
			statement.setObject(3, transactionId);
			statement.setBigDecimal(4, new java.math.BigDecimal(amount));
			statement.setString(5, currency);
			statement.setObject(6, java.time.OffsetDateTime.parse(settledAt));
			statement.setString(7, rowHash);
			statement.setObject(8, importId);
			statement.setInt(9, sourceRowNumber);
			statement.executeUpdate();
		}
	}

	@Test
	void conflictingTransactionIdReturns409() throws SQLException {
		String source = uniqueSource();
		UUID originalTxn = UUID.randomUUID();
		byte[] first = csv(row("EXT-803", originalTxn, "10.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		postImport(source, first);

		byte[] conflicting = csv(row("EXT-803", UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		ResponseEntity<Map> response = postImport(source, conflicting);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		Map<String, Object> preserved = fetchRecordByExternalReference(SourceNormalizer.normalize(source), "EXT-803");
		assertThat(preserved.get("transaction_id")).isEqualTo(originalTxn);
	}

	@Test
	void conflictingTimestampReturns409() throws SQLException {
		assertConflictRollsBackWholeImport(
				row("EXT-804", UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"),
				txnId -> row("EXT-804", txnId, "10.00", "USD", "2026-07-16T10:00:00Z"));
	}

	private interface ConflictingRowBuilder {
		String build(UUID transactionId);
	}

	private void assertConflictRollsBackWholeImport(String originalRow, ConflictingRowBuilder conflictingRowBuilder)
			throws SQLException {
		String source = uniqueSource();
		byte[] first = csv(originalRow).getBytes(StandardCharsets.UTF_8);
		ResponseEntity<Map> firstResponse = postImport(source, first);
		assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		long importsBefore = countRows("settlement_import");
		long recordsBefore = countRows("settlement_record");

		byte[] conflictingFile = csv(
				row("EXT-EARLIER-" + UUID.randomUUID(), UUID.randomUUID(), "1.00", "USD", "2026-07-15T10:00:00Z"),
				conflictingRowBuilder.build(UUID.randomUUID()))
				.getBytes(StandardCharsets.UTF_8);
		ResponseEntity<Map> conflictResponse = postImport(source, conflictingFile);

		assertThat(conflictResponse.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		// No partial import: not even the earlier, non-conflicting row in
		// the same file was persisted.
		assertThat(countRows("settlement_import")).isEqualTo(importsBefore);
		assertThat(countRows("settlement_record")).isEqualTo(recordsBefore);
	}

	@Test
	void retryAfterARolledBackConflictSucceedsWithACleanFile() {
		String source = uniqueSource();
		UUID txn = UUID.randomUUID();
		postImport(source, csv(row("EXT-901", txn, "10.00", "USD", "2026-07-15T10:00:00Z")).getBytes(StandardCharsets.UTF_8));

		byte[] conflicting = csv(row("EXT-901", UUID.randomUUID(), "20.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		assertThat(postImport(source, conflicting).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		byte[] clean = csv(row("EXT-902", UUID.randomUUID(), "30.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		ResponseEntity<Map> retryResponse = postImport(source, clean);
		assertThat(retryResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
	}

	// ------------------------------------------------------------------
	// transaction behavior
	// ------------------------------------------------------------------

	@Test
	void anInvalidRowCreatesNoImportOrObservation() throws SQLException {
		long importsBefore = countRows("settlement_import");
		long recordsBefore = countRows("settlement_record");

		String source = uniqueSource();
		byte[] content = csv(row("EXT-1001", UUID.randomUUID(), "not-a-number", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		ResponseEntity<Map> response = postImport(source, content);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(countRows("settlement_import")).isEqualTo(importsBefore);
		assertThat(countRows("settlement_record")).isEqualTo(recordsBefore);
	}

	@Test
	void aConflictInTheFinalRowRollsBackAllEarlierRowsInTheSameFile() throws SQLException {
		String source = uniqueSource();
		postImport(source, csv(row("EXT-1101", UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8));

		long importsBefore = countRows("settlement_import");
		long recordsBefore = countRows("settlement_record");

		byte[] fileWithLateConflict = csv(
				row("EXT-1102", UUID.randomUUID(), "1.00", "USD", "2026-07-15T10:00:00Z"),
				row("EXT-1103", UUID.randomUUID(), "2.00", "USD", "2026-07-15T10:00:00Z"),
				row("EXT-1101", UUID.randomUUID(), "999.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		ResponseEntity<Map> response = postImport(source, fileWithLateConflict);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
		assertThat(countRows("settlement_import")).isEqualTo(importsBefore);
		assertThat(countRows("settlement_record")).isEqualTo(recordsBefore);
	}

	@Test
	void returned201MeansTheTransactionCommitted() throws SQLException {
		String source = uniqueSource();
		byte[] content = csv(row("EXT-1201", UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		ResponseEntity<Map> response = postImport(source, content);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

		UUID importId = UUID.fromString((String) response.getBody().get("importId"));
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM settlement_import WHERE id = ?")) {
			statement.setObject(1, importId);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				assertThat(resultSet.getInt(1)).isEqualTo(1);
			}
		}
	}

	@Test
	void returnedReplayResultRefersToTheCommittedOriginalImport() {
		String source = uniqueSource();
		byte[] content = csv(row("EXT-1301", UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		ResponseEntity<Map> original = postImport(source, content);
		ResponseEntity<Map> replay = postImport(source, content);
		assertThat(replay.getBody().get("importedAt")).isEqualTo(original.getBody().get("importedAt"));
	}

	// ------------------------------------------------------------------
	// financial non-effects
	// ------------------------------------------------------------------

	@Test
	void importingSettlementFilesNeverMutatesAnyFinancialOrEventState() throws SQLException {
		long accountsBefore = countRows("account");
		java.math.BigDecimal balancesSnapshotBefore = sumBalances();
		long transactionsBefore = countRows("ledger_transaction");
		long entriesBefore = countRows("ledger_entry");
		long idempotencyBefore = countRows("idempotency_key");
		long outboxBefore = countRows("outbox_event");
		long processedEventBefore = countRows("processed_event");

		String source = uniqueSource();
		byte[] content = csv(
				row("EXT-1401", UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"),
				row("EXT-1402", UUID.randomUUID(), "20.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		assertThat(postImport(source, content).getStatusCode()).isEqualTo(HttpStatus.CREATED);

		assertThat(countRows("account")).isEqualTo(accountsBefore);
		assertThat(sumBalances()).isEqualByComparingTo(balancesSnapshotBefore);
		assertThat(countRows("ledger_transaction")).isEqualTo(transactionsBefore);
		assertThat(countRows("ledger_entry")).isEqualTo(entriesBefore);
		assertThat(countRows("idempotency_key")).isEqualTo(idempotencyBefore);
		assertThat(countRows("outbox_event")).isEqualTo(outboxBefore);
		assertThat(countRows("processed_event")).isEqualTo(processedEventBefore);
	}

	// ------------------------------------------------------------------
	// request-level validation
	// ------------------------------------------------------------------

	@Test
	void emptyFileReturns400() {
		ResponseEntity<Map> response = postImport(uniqueSource(), new byte[0]);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void headerOnlyFileReturns400() {
		ResponseEntity<Map> response = postImport(uniqueSource(), (HEADER + "\n").getBytes(StandardCharsets.UTF_8));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void invalidRowReturns400() {
		byte[] content = csv(row("EXT-1501", UUID.randomUUID(), "-5.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		assertThat(postImport(uniqueSource(), content).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void blankSourceReturns400() {
		byte[] content = csv(row("EXT-1601", UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		assertThat(postImport("   ", content).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void unsupportedContentTypeReturns415() {
		byte[] content = csv(row("EXT-1701", UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		ResponseEntity<Map> response = postImport(uniqueSource(), content, "settlement.csv", MediaType.IMAGE_PNG);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
	}

	@Test
	void fileExceedingTheConfiguredLimitReturns413() {
		// Larger than ledgerguard.settlement.import.max-file-size-bytes
		// (5 MiB default) but still under Spring's own outer multipart
		// boundary (6 MB), so this exercises Task 14's own limit, not the
		// framework's.
		StringBuilder oversized = new StringBuilder(HEADER).append('\n');
		while (oversized.length() < 5 * 1024 * 1024 + 1024) {
			oversized.append(row("EXT-" + oversized.length(), UUID.randomUUID(), "1.00", "USD", "2026-07-15T10:00:00Z"))
					.append('\n');
		}
		ResponseEntity<Map> response = postImport(uniqueSource(), oversized.toString().getBytes(StandardCharsets.UTF_8));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
	}

	@Test
	void rowCountExceedingTheConfiguredLimitReturns413() {
		StringBuilder content = new StringBuilder(HEADER).append('\n');
		for (int i = 0; i < 10_001; i++) {
			content.append(row("EXT-ROW-" + i, UUID.randomUUID(), "1.00", "USD", "2026-07-15T10:00:00Z")).append('\n');
		}
		ResponseEntity<Map> response = postImport(uniqueSource(), content.toString().getBytes(StandardCharsets.UTF_8));
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
	}

	// ------------------------------------------------------------------
	// concurrency (PostgreSQL constraint arbitration, not JVM locks)
	// ------------------------------------------------------------------

	@Test
	void twoConcurrentIdenticalFileImportsProduceOneImportRow() throws Exception {
		String source = uniqueSource();
		byte[] content = csv(row("EXT-2001", UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);

		List<ResponseEntity<Map>> results = runConcurrently(2, () -> postImport(source, content));

		assertThat(results).allMatch(r -> r.getStatusCode() == HttpStatus.CREATED || r.getStatusCode() == HttpStatus.OK);
		assertThat(results).anyMatch(r -> r.getStatusCode() == HttpStatus.CREATED);
		assertThat(results).anyMatch(r -> Boolean.TRUE.equals(r.getBody().get("replayed")));

		String normalizedSource = SourceNormalizer.normalize(source);
		assertThat(countImportsForSource(normalizedSource)).isEqualTo(1);
	}

	@Test
	void concurrentByteDistinctFilesWithAnIdenticalRowProduceOneObservation() throws Exception {
		String source = uniqueSource();
		UUID sharedTxn = UUID.randomUUID();
		String sharedRow = row("EXT-2101", sharedTxn, "10.00", "USD", "2026-07-15T10:00:00Z");
		byte[] fileA = csv(sharedRow, row("EXT-2102", UUID.randomUUID(), "1.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		byte[] fileB = csv(sharedRow, row("EXT-2103", UUID.randomUUID(), "1.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		assertThat(Sha256.hex(fileA)).isNotEqualTo(Sha256.hex(fileB));

		List<ResponseEntity<Map>> results = runConcurrently(2, List.of(
				() -> postImport(source, fileA),
				() -> postImport(source, fileB)));

		assertThat(results).allMatch(r -> r.getStatusCode() == HttpStatus.CREATED);

		String normalizedSource = SourceNormalizer.normalize(source);
		assertThat(countRecordsForReference(normalizedSource, "EXT-2101")).isEqualTo(1);
		assertThat(countImportsForSource(normalizedSource)).isEqualTo(2);
	}

	@Test
	void concurrentConflictingRowsProduceOneWinnerAndOneConflict() throws Exception {
		String source = uniqueSource();
		String ref = "EXT-2201";
		byte[] fileA = csv(row(ref, UUID.randomUUID(), "10.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);
		byte[] fileB = csv(row(ref, UUID.randomUUID(), "20.00", "USD", "2026-07-15T10:00:00Z"))
				.getBytes(StandardCharsets.UTF_8);

		List<ResponseEntity<Map>> results = runConcurrently(2, List.of(
				() -> postImport(source, fileA),
				() -> postImport(source, fileB)));

		long successCount = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
		long conflictCount = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();
		assertThat(successCount).isEqualTo(1);
		assertThat(conflictCount).isEqualTo(1);

		assertThat(countRecordsForReference(SourceNormalizer.normalize(source), ref)).isEqualTo(1);
	}

	private List<ResponseEntity<Map>> runConcurrently(int parallelism, Callable<ResponseEntity<Map>> task) throws Exception {
		List<Callable<ResponseEntity<Map>>> tasks = new ArrayList<>();
		for (int i = 0; i < parallelism; i++) {
			tasks.add(task);
		}
		return runConcurrently(parallelism, tasks);
	}

	private List<ResponseEntity<Map>> runConcurrently(int parallelism, List<Callable<ResponseEntity<Map>>> tasks)
			throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(parallelism);
		CountDownLatch ready = new CountDownLatch(parallelism);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Callable<ResponseEntity<Map>>> gated = new ArrayList<>();
			for (Callable<ResponseEntity<Map>> task : tasks) {
				gated.add(() -> {
					ready.countDown();
					start.await(10, TimeUnit.SECONDS);
					return task.call();
				});
			}
			List<Future<ResponseEntity<Map>>> futures = executor.invokeAll(gated);
			List<ResponseEntity<Map>> results = new ArrayList<>();
			for (Future<ResponseEntity<Map>> future : futures) {
				results.add(future.get(30, TimeUnit.SECONDS));
			}
			return results;
		} finally {
			executor.shutdown();
			executor.awaitTermination(10, TimeUnit.SECONDS);
		}
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private UUID createDepositTransactionId(java.math.BigDecimal amount) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO ledger_transaction (transaction_type, status) VALUES ('DEPOSIT', 'COMPLETED') RETURNING id")) {
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return (UUID) resultSet.getObject("id");
			}
		}
	}

	private Map<String, Object> fetchRecordByExternalReference(String normalizedSource, String externalReference)
			throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT * FROM settlement_record WHERE normalized_source = ? AND external_reference = ?")) {
			statement.setString(1, normalizedSource);
			statement.setString(2, externalReference);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return Map.of(
						"transaction_id", resultSet.getObject("transaction_id"),
						"amount", resultSet.getBigDecimal("amount"),
						"currency", resultSet.getString("currency"),
						"source_row_number", resultSet.getInt("source_row_number"),
						"first_import_id", resultSet.getObject("first_import_id"),
						"row_hash", resultSet.getString("row_hash"));
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

	private java.math.BigDecimal sumBalances() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery("SELECT SUM(balance) FROM account")) {
			resultSet.next();
			java.math.BigDecimal sum = resultSet.getBigDecimal(1);
			return sum == null ? java.math.BigDecimal.ZERO : sum;
		}
	}

	private long countImportsForSource(String normalizedSource) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM settlement_import WHERE normalized_source = ?")) {
			statement.setString(1, normalizedSource);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getLong(1);
			}
		}
	}

	private long countRecordsForReference(String normalizedSource, String externalReference) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM settlement_record WHERE normalized_source = ? AND external_reference = ?")) {
			statement.setString(1, normalizedSource);
			statement.setString(2, externalReference);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getLong(1);
			}
		}
	}

}
