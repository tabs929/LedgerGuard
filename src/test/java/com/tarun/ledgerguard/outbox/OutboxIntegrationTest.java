package com.tarun.ledgerguard.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 11: the transactional outbox. Verifies that exactly one
 * {@code outbox_event} row commits atomically with a new deposit or
 * transfer's ledger transaction, entries, balance updates, and Task 10
 * idempotency record — and that a Task 10 replay or conflict never
 * inserts one — against a real, isolated PostgreSQL 16.4 Testcontainer.
 * No PostgreSQL behavior is mocked; concurrency assertions use bounded
 * {@code invokeAll}/{@code get} timeouts, never {@code Thread.sleep}, as
 * the correctness mechanism.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	TestRestTemplate restTemplate;

	@Autowired
	DataSource dataSource;

	private static final ObjectMapper JSON = new ObjectMapper();

	// ------------------------------------------------------------------
	// deposit success
	// ------------------------------------------------------------------

	@Test
	void successfulDepositCreatesExactlyOneOutboxEventWithTheApprovedPayload() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Deposit Owner");
		String key = UUID.randomUUID().toString();

		ResponseEntity<Map> response = postDeposit(accountId, "100.00", key);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));

		List<Map<String, Object>> rows = fetchOutboxRowsForAggregate(transactionId);
		assertThat(rows).hasSize(1);
		Map<String, Object> row = rows.get(0);

		assertThat(row.get("event_type")).isEqualTo("DEPOSIT_COMPLETED");
		assertThat(row.get("aggregate_type")).isEqualTo("LEDGER_TRANSACTION");
		assertThat(row.get("aggregate_id")).isEqualTo(transactionId);
		assertThat(row.get("schema_version")).isEqualTo(1);
		assertThat(row.get("published_at")).isNull();

		JsonNode payload = JSON.readTree((String) row.get("payload"));
		assertThat(fieldNames(payload)).containsExactlyInAnyOrder(
				"eventId", "eventType", "schemaVersion", "occurredAt", "transactionId",
				"destinationAccountId", "amount", "currency");
		assertThat(payload.get("eventId").asText()).isEqualTo(row.get("id").toString());
		assertThat(payload.get("eventType").asText()).isEqualTo("DEPOSIT_COMPLETED");
		assertThat(payload.get("schemaVersion").asInt()).isEqualTo(1);
		assertThat(payload.get("transactionId").asText()).isEqualTo(transactionId.toString());
		assertThat(payload.get("destinationAccountId").asText()).isEqualTo(accountId.toString());
		assertThat(payload.get("amount").isTextual()).isTrue();
		assertThat(payload.get("amount").asText()).isEqualTo("100.0000");
		assertThat(payload.get("currency").asText()).isEqualTo("USD");

		Instant ledgerCreatedAt = fetchLedgerTransactionCreatedAt(transactionId);
		assertThat((Instant) row.get("occurred_at")).isEqualTo(ledgerCreatedAt);
		assertThat(OffsetDateTime.parse(payload.get("occurredAt").asText()).toInstant()).isEqualTo(ledgerCreatedAt);

		String rawPayload = (String) row.get("payload");
		assertThat(rawPayload).doesNotContain(fetchUsdFundingAccountId().toString());
	}

	// ------------------------------------------------------------------
	// transfer success
	// ------------------------------------------------------------------

	@Test
	void successfulTransferCreatesExactlyOneOutboxEventWithTheApprovedPayload() throws Exception {
		UUID sourceId = createFundedAccount("Outbox Transfer Source", "100.00");
		UUID destinationId = createUsdCustomerAccount("Outbox Transfer Destination");
		String key = UUID.randomUUID().toString();

		ResponseEntity<Map> response = postTransfer(sourceId, destinationId, "30.00", key);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));

		List<Map<String, Object>> rows = fetchOutboxRowsForAggregate(transactionId);
		assertThat(rows).hasSize(1);
		Map<String, Object> row = rows.get(0);

		assertThat(row.get("event_type")).isEqualTo("TRANSFER_COMPLETED");
		assertThat(row.get("aggregate_id")).isEqualTo(transactionId);
		assertThat(row.get("published_at")).isNull();

		JsonNode payload = JSON.readTree((String) row.get("payload"));
		assertThat(fieldNames(payload)).containsExactlyInAnyOrder(
				"eventId", "eventType", "schemaVersion", "occurredAt", "transactionId",
				"sourceAccountId", "destinationAccountId", "amount", "currency");
		assertThat(payload.get("sourceAccountId").asText()).isEqualTo(sourceId.toString());
		assertThat(payload.get("destinationAccountId").asText()).isEqualTo(destinationId.toString());
		assertThat(payload.get("amount").asText()).isEqualTo("30.0000");
		assertThat(payload.get("currency").asText()).isEqualTo("USD");

		Instant ledgerCreatedAt = fetchLedgerTransactionCreatedAt(transactionId);
		assertThat((Instant) row.get("occurred_at")).isEqualTo(ledgerCreatedAt);
	}

	// ------------------------------------------------------------------
	// idempotent behavior
	// ------------------------------------------------------------------

	@Test
	void identicalDepositRetryLeavesExactlyOneOutboxRow() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Deposit Replay");
		String key = UUID.randomUUID().toString();
		long before = countOutboxEvents();

		postDeposit(accountId, "20.00", key);
		postDeposit(accountId, "20.00", key);

		assertThat(countOutboxEvents()).isEqualTo(before + 1);
	}

	@Test
	void numericallyEquivalentDepositRetryLeavesExactlyOneOutboxRow() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Deposit Replay Formatting");
		String key = UUID.randomUUID().toString();

		postDeposit(accountId, "50", key);
		postDeposit(accountId, "50.00", key);

		ResponseEntity<Map> response = postDeposit(accountId, "50.0000", key);
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));
		assertThat(fetchOutboxRowsForAggregate(transactionId)).hasSize(1);
	}

	@Test
	void identicalTransferRetryLeavesExactlyOneOutboxRow() throws Exception {
		UUID sourceId = createFundedAccount("Outbox Transfer Replay Source", "100.00");
		UUID destinationId = createUsdCustomerAccount("Outbox Transfer Replay Destination");
		String key = UUID.randomUUID().toString();

		ResponseEntity<Map> first = postTransfer(sourceId, destinationId, "15.00", key);
		postTransfer(sourceId, destinationId, "15.00", key);

		UUID transactionId = UUID.fromString((String) first.getBody().get("transactionId"));
		assertThat(fetchOutboxRowsForAggregate(transactionId)).hasSize(1);
	}

	@Test
	void conflictingDepositRetryCreatesNoAdditionalOutboxRow() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Deposit Conflict");
		String key = UUID.randomUUID().toString();
		postDeposit(accountId, "10.00", key);
		long countAfterFirst = countOutboxEvents();

		ResponseEntity<String> conflict = postDepositRaw(accountId, "20.00", key);
		assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		assertThat(countOutboxEvents()).isEqualTo(countAfterFirst);
	}

	@Test
	void conflictingTransferRetryCreatesNoAdditionalOutboxRow() throws Exception {
		UUID sourceId = createFundedAccount("Outbox Transfer Conflict Source", "100.00");
		UUID destinationId = createUsdCustomerAccount("Outbox Transfer Conflict Destination");
		UUID otherDestinationId = createUsdCustomerAccount("Outbox Transfer Conflict Other Destination");
		String key = UUID.randomUUID().toString();
		postTransfer(sourceId, destinationId, "10.00", key);
		long countAfterFirst = countOutboxEvents();

		ResponseEntity<String> conflict = postTransferRaw(sourceId, otherDestinationId, "10.00", key);
		assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		assertThat(countOutboxEvents()).isEqualTo(countAfterFirst);
	}

	@Test
	void crossOperationKeyConflictCreatesNoAdditionalOutboxRow() throws Exception {
		UUID accountId = createFundedAccount("Outbox Cross Op Source", "50.00");
		UUID destinationId = createUsdCustomerAccount("Outbox Cross Op Destination");
		String key = UUID.randomUUID().toString();
		postDeposit(accountId, "10.00", key);
		long countAfterFirst = countOutboxEvents();

		ResponseEntity<String> conflict = postTransferRaw(accountId, destinationId, "10.00", key);
		assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

		assertThat(countOutboxEvents()).isEqualTo(countAfterFirst);
	}

	@Test
	void concurrentIdenticalDepositRequestsCreateExactlyOneOutboxRow() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Concurrent Identical Deposit");
		String key = UUID.randomUUID().toString();
		long before = countOutboxEvents();

		int concurrentRequests = 12;
		ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
		try {
			List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
			for (int i = 0; i < concurrentRequests; i++) {
				tasks.add(() -> postDepositRaw(accountId, "5.00", key));
			}
			List<Future<ResponseEntity<String>>> futures = executor.invokeAll(tasks, 60, TimeUnit.SECONDS);
			for (Future<ResponseEntity<String>> future : futures) {
				assertThat(future.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
			}
		}
		finally {
			executor.shutdown();
		}

		assertThat(countOutboxEvents()).isEqualTo(before + 1);
	}

	@Test
	void concurrentIdenticalTransferRequestsCreateExactlyOneOutboxRow() throws Exception {
		UUID sourceId = createFundedAccount("Outbox Concurrent Identical Transfer Source", "100.00");
		UUID destinationId = createUsdCustomerAccount("Outbox Concurrent Identical Transfer Destination");
		String key = UUID.randomUUID().toString();
		long before = countOutboxEvents();

		int concurrentRequests = 12;
		ExecutorService executor = Executors.newFixedThreadPool(concurrentRequests);
		try {
			List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
			for (int i = 0; i < concurrentRequests; i++) {
				tasks.add(() -> postTransferRaw(sourceId, destinationId, "3.00", key));
			}
			List<Future<ResponseEntity<String>>> futures = executor.invokeAll(tasks, 60, TimeUnit.SECONDS);
			for (Future<ResponseEntity<String>> future : futures) {
				assertThat(future.get(5, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.CREATED);
			}
		}
		finally {
			executor.shutdown();
		}

		assertThat(countOutboxEvents()).isEqualTo(before + 1);
	}

	@Test
	void concurrentConflictingDepositRequestsCreateAnEventOnlyForTheWinningCommand() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Concurrent Conflicting Deposit");
		String key = UUID.randomUUID().toString();
		long before = countOutboxEvents();

		int perGroup = 6;
		ExecutorService executor = Executors.newFixedThreadPool(perGroup * 2);
		try {
			List<Callable<ResponseEntity<String>>> tasks = new ArrayList<>();
			for (int i = 0; i < perGroup; i++) {
				tasks.add(() -> postDepositRaw(accountId, "10.00", key));
				tasks.add(() -> postDepositRaw(accountId, "40.00", key));
			}
			List<Future<ResponseEntity<String>>> futures = executor.invokeAll(tasks, 60, TimeUnit.SECONDS);

			List<ResponseEntity<String>> results = new ArrayList<>();
			for (Future<ResponseEntity<String>> future : futures) {
				results.add(future.get(5, TimeUnit.SECONDS));
			}
			long createdCount = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
			long conflictCount = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();
			assertThat(createdCount).isGreaterThan(0);
			assertThat(createdCount + conflictCount).isEqualTo(perGroup * 2L);
		}
		finally {
			executor.shutdown();
		}

		assertThat(countOutboxEvents()).isEqualTo(before + 1);
		BigDecimal winningAmount = fetchBalance(accountId);
		assertThat(winningAmount).isIn(new BigDecimal("10.0000"), new BigDecimal("40.0000"));
	}

	// ------------------------------------------------------------------
	// rollback behavior
	// ------------------------------------------------------------------

	@Test
	void invalidDepositCreatesNoOutboxRow() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Invalid Deposit");
		long before = countOutboxEvents();

		ResponseEntity<String> response = postDepositRaw(accountId, "-1.00", UUID.randomUUID().toString());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

		assertThat(countOutboxEvents()).isEqualTo(before);
	}

	@Test
	void nonexistentDepositAccountCreatesNoOutboxRow() throws Exception {
		long before = countOutboxEvents();

		ResponseEntity<String> response = postDepositRaw(UUID.randomUUID(), "10.00", UUID.randomUUID().toString());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		assertThat(countOutboxEvents()).isEqualTo(before);
	}

	@Test
	void insufficientFundsTransferCreatesNoOutboxRow() throws Exception {
		UUID sourceId = createUsdCustomerAccount("Outbox Insufficient Funds Source");
		UUID destinationId = createUsdCustomerAccount("Outbox Insufficient Funds Destination");
		long before = countOutboxEvents();

		ResponseEntity<String> response =
				postTransferRaw(sourceId, destinationId, "10.00", UUID.randomUUID().toString());
		assertThat(response.getStatusCode().value()).isEqualTo(422);

		assertThat(countOutboxEvents()).isEqualTo(before);
	}

	@Test
	void nonexistentTransferAccountCreatesNoOutboxRow() throws Exception {
		UUID sourceId = createFundedAccount("Outbox Nonexistent Destination Source", "10.00");
		long before = countOutboxEvents();

		ResponseEntity<String> response =
				postTransferRaw(sourceId, UUID.randomUUID(), "5.00", UUID.randomUUID().toString());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

		assertThat(countOutboxEvents()).isEqualTo(before);
	}

	@Test
	void forcedLedgerDatabaseFailureCreatesNoOutboxRow() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Ledger Overflow");
		setBalanceDirectly(accountId, new BigDecimal("999999999999999.0000"));
		long before = countOutboxEvents();

		ResponseEntity<String> response = postDepositRaw(accountId, "10.00", UUID.randomUUID().toString());
		assertThat(response.getStatusCode().is5xxServerError()).isTrue();

		assertThat(countOutboxEvents()).isEqualTo(before);
	}

	@Test
	void forcedOutboxInsertionFailureRollsBackTheWholeOperationAndKeySucceedsAfterCorrection() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Forced Insertion Failure");
		String key = UUID.randomUUID().toString();
		long transactionsBefore = countLedgerTransactions();
		long outboxBefore = countOutboxEvents();

		// A genuine PostgreSQL-level failure, deterministic and independent
		// of the randomly-generated ledger_transaction id: block every new
		// outbox_event insert with a real CHECK constraint (NOT VALID so it
		// doesn't retroactively reject rows already committed by earlier
		// tests sharing this container), forcing the outbox insert -- and
		// only the outbox insert, since the ledger/entry/balance writes
		// earlier in the same method already succeeded their own flush --
		// to fail.
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("ALTER TABLE outbox_event ADD CONSTRAINT chk_test_block_insert CHECK (1 = 0) NOT VALID");
		}

		try {
			ResponseEntity<String> response = postDepositRaw(accountId, "10.00", key);
			assertThat(response.getStatusCode().is5xxServerError()).isTrue();

			assertThat(countLedgerTransactions()).isEqualTo(transactionsBefore);
			assertThat(countOutboxEvents()).isEqualTo(outboxBefore);
			assertThat(fetchBalance(accountId)).isEqualByComparingTo("0.0000");
			assertThat(countIdempotencyRows(key)).isEqualTo(0);
		}
		finally {
			try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
				statement.execute("ALTER TABLE outbox_event DROP CONSTRAINT chk_test_block_insert");
			}
		}

		ResponseEntity<Map> retried = postDeposit(accountId, "10.00", key);
		assertThat(retried.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		UUID transactionId = UUID.fromString((String) retried.getBody().get("transactionId"));
		assertThat(fetchOutboxRowsForAggregate(transactionId)).hasSize(1);
		assertThat(countOutboxEvents()).isEqualTo(outboxBefore + 1);
	}

	// ------------------------------------------------------------------
	// constraint behavior
	// ------------------------------------------------------------------

	@Test
	void duplicateEventIdentityIsRejectedByPostgres() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Duplicate Identity");
		ResponseEntity<Map> response = postDeposit(accountId, "5.00", UUID.randomUUID().toString());
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));

		assertThatSqlFails(() -> insertRawOutboxEvent(transactionId, "LEDGER_TRANSACTION", "DEPOSIT_COMPLETED",
				validPayload()), "uq_outbox_event_identity");
	}

	@Test
	void invalidAggregateTypeIsRejected() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Invalid Aggregate Type");
		ResponseEntity<Map> response = postDeposit(accountId, "5.00", UUID.randomUUID().toString());
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));

		assertThatSqlFails(() -> insertRawOutboxEvent(UUID.randomUUID(), "ACCOUNT", "DEPOSIT_COMPLETED",
				validPayloadForTransaction(transactionId)), "chk_outbox_aggregate_type");
	}

	@Test
	void invalidEventTypeIsRejected() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Invalid Event Type");
		ResponseEntity<Map> response = postDeposit(accountId, "5.00", UUID.randomUUID().toString());
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));

		assertThatSqlFails(() -> insertRawOutboxEvent(UUID.randomUUID(), "LEDGER_TRANSACTION", "SOMETHING_ELSE",
				validPayloadForTransaction(transactionId)), "chk_outbox_event_type");
	}

	@Test
	void invalidSchemaVersionIsRejected() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Invalid Schema Version");
		ResponseEntity<Map> response = postDeposit(accountId, "5.00", UUID.randomUUID().toString());
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));

		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, schema_version, "
								+ "payload, occurred_at) VALUES (?, 'LEDGER_TRANSACTION', ?, 'DEPOSIT_COMPLETED', 0, "
								+ "?::jsonb, now())")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, transactionId);
			statement.setString(3, validPayloadForTransaction(transactionId));
			assertThatSqlFails(statement, "chk_outbox_schema_version_positive");
		}
	}

	@Test
	void nonObjectJsonPayloadIsRejected() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Non Object Payload");
		ResponseEntity<Map> response = postDeposit(accountId, "5.00", UUID.randomUUID().toString());
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));

		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, schema_version, "
								+ "payload, occurred_at) VALUES (?, 'LEDGER_TRANSACTION', ?, 'TRANSFER_COMPLETED', 1, "
								+ "'[1,2,3]'::jsonb, now())")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setObject(2, transactionId);
			assertThatSqlFails(statement, "chk_outbox_payload_is_object");
		}
	}

	@Test
	void nonexistentLedgerTransactionAggregateIdIsRejected() throws Exception {
		assertThatSqlFails(() -> insertRawOutboxEvent(UUID.randomUUID(), "LEDGER_TRANSACTION", "DEPOSIT_COMPLETED",
				validPayload()), null);
	}

	@Test
	void immutableEventFieldsCannotBeUpdated() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Immutable Fields");
		ResponseEntity<Map> response = postDeposit(accountId, "5.00", UUID.randomUUID().toString());
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));
		UUID eventId = fetchOutboxRowsForAggregate(transactionId).get(0).get("id") instanceof UUID id ? id : null;
		assertThat(eventId).isNotNull();

		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"UPDATE outbox_event SET event_type = 'TRANSFER_COMPLETED' WHERE id = ?")) {
			statement.setObject(1, eventId);
			assertThatSqlFails(statement, "immutable");
		}

		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"UPDATE outbox_event SET payload = '{}'::jsonb WHERE id = ?")) {
			statement.setObject(1, eventId);
			assertThatSqlFails(statement, "immutable");
		}
	}

	@Test
	void eventRowsCannotBeDeleted() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox No Delete");
		ResponseEntity<Map> response = postDeposit(accountId, "5.00", UUID.randomUUID().toString());
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));
		UUID eventId = fetchOutboxRowsForAggregate(transactionId).get(0).get("id") instanceof UUID id ? id : null;

		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("DELETE FROM outbox_event WHERE id = ?")) {
			statement.setObject(1, eventId);
			assertThatSqlFails(statement, "immutable");
		}
	}

	@Test
	void publishedAtFollowsTheApprovedTransitionRules() throws Exception {
		UUID accountId = createUsdCustomerAccount("Outbox Published At Transition");
		ResponseEntity<Map> response = postDeposit(accountId, "5.00", UUID.randomUUID().toString());
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));
		UUID eventId = fetchOutboxRowsForAggregate(transactionId).get(0).get("id") instanceof UUID id ? id : null;

		// NULL -> non-null is permitted.
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"UPDATE outbox_event SET published_at = now() WHERE id = ?")) {
			statement.setObject(1, eventId);
			assertThat(statement.executeUpdate()).isEqualTo(1);
		}

		// Once set, it cannot be cleared or overwritten.
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"UPDATE outbox_event SET published_at = NULL WHERE id = ?")) {
			statement.setObject(1, eventId);
			assertThatSqlFails(statement, "published_at");
		}
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"UPDATE outbox_event SET published_at = now() WHERE id = ?")) {
			statement.setObject(1, eventId);
			assertThatSqlFails(statement, "published_at");
		}
	}

	// ------------------------------------------------------------------
	// migration behavior
	// ------------------------------------------------------------------

	@Test
	void v1V2AndV3AllApplyFromAnEmptySchemaAndFlywayValidationSucceeds() throws SQLException {
		Set<String> versions = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT version, success FROM flyway_schema_history WHERE version IN ('1', '2', '3')")) {
			while (resultSet.next()) {
				assertThat(resultSet.getBoolean("success")).isTrue();
				versions.add(resultSet.getString("version"));
			}
		}
		assertThat(versions).containsExactlyInAnyOrder("1", "2", "3");
	}

	@Test
	void outboxTableConstraintsIndexesAndTriggersExist() throws SQLException {
		Set<String> constraints = constraintNames("outbox_event");
		assertThat(constraints).contains(
				"chk_outbox_aggregate_type", "chk_outbox_event_type", "chk_outbox_schema_version_positive",
				"chk_outbox_payload_is_object", "uq_outbox_event_identity");

		Set<String> indexes = indexNames("outbox_event");
		assertThat(indexes).contains("idx_outbox_event_pending");

		Set<String> triggers = triggerNames("outbox_event");
		assertThat(triggers).contains("trg_outbox_event_no_delete", "trg_outbox_event_immutable");
	}

	@Test
	void v1AndV2TablesAndConstraintsStillExistUnchanged() throws SQLException {
		assertThat(constraintNames("account")).contains("chk_account_taxonomy_combination");
		assertThat(constraintNames("idempotency_key")).contains("uq_idempotency_key");
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private UUID createUsdCustomerAccount(String ownerName) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		ResponseEntity<Map> response = restTemplate.postForEntity(
				"/api/v1/accounts", new HttpEntity<>(Map.of("ownerName", ownerName, "currency", "USD"), headers),
				Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return UUID.fromString((String) response.getBody().get("id"));
	}

	private UUID createFundedAccount(String ownerName, String amount) {
		UUID accountId = createUsdCustomerAccount(ownerName);
		ResponseEntity<Map> response = postDeposit(accountId, amount, UUID.randomUUID().toString());
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return accountId;
	}

	private ResponseEntity<Map> postDeposit(UUID accountId, String amount, String idempotencyKey) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Idempotency-Key", idempotencyKey);
		return restTemplate.postForEntity("/api/v1/accounts/" + accountId + "/deposits",
				new HttpEntity<>(Map.of("amount", amount, "currency", "USD"), headers), Map.class);
	}

	private ResponseEntity<String> postDepositRaw(UUID accountId, String amount, String idempotencyKey) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Idempotency-Key", idempotencyKey);
		return restTemplate.postForEntity("/api/v1/accounts/" + accountId + "/deposits",
				new HttpEntity<>(Map.of("amount", amount, "currency", "USD"), headers), String.class);
	}

	private ResponseEntity<Map> postTransfer(UUID sourceId, UUID destinationId, String amount, String idempotencyKey) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Idempotency-Key", idempotencyKey);
		Map<String, Object> body = Map.of("sourceAccountId", sourceId.toString(),
				"destinationAccountId", destinationId.toString(), "amount", amount, "currency", "USD");
		return restTemplate.postForEntity("/api/v1/transfers", new HttpEntity<>(body, headers), Map.class);
	}

	private ResponseEntity<String> postTransferRaw(UUID sourceId, UUID destinationId, String amount, String idempotencyKey) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.set("Idempotency-Key", idempotencyKey);
		Map<String, Object> body = Map.of("sourceAccountId", sourceId.toString(),
				"destinationAccountId", destinationId.toString(), "amount", amount, "currency", "USD");
		return restTemplate.postForEntity("/api/v1/transfers", new HttpEntity<>(body, headers), String.class);
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

	private void setBalanceDirectly(UUID accountId, BigDecimal balance) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement("UPDATE account SET balance = ? WHERE id = ?")) {
			statement.setBigDecimal(1, balance);
			statement.setObject(2, accountId);
			statement.executeUpdate();
		}
	}

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

	private long countLedgerTransactions() throws SQLException {
		return countRows("SELECT COUNT(*) FROM ledger_transaction");
	}

	private long countOutboxEvents() throws SQLException {
		return countRows("SELECT COUNT(*) FROM outbox_event");
	}

	private long countIdempotencyRows(String idempotencyKey) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM idempotency_key WHERE idempotency_key = ?")) {
			statement.setString(1, idempotencyKey);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getLong(1);
			}
		}
	}

	private long countRows(String sql) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(sql)) {
			resultSet.next();
			return resultSet.getLong(1);
		}
	}

	private Instant fetchLedgerTransactionCreatedAt(UUID transactionId) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT created_at FROM ledger_transaction WHERE id = ?")) {
			statement.setObject(1, transactionId);
			try (ResultSet resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).isTrue();
				return resultSet.getObject("created_at", OffsetDateTime.class).toInstant();
			}
		}
	}

	private List<Map<String, Object>> fetchOutboxRowsForAggregate(UUID aggregateId) throws SQLException {
		List<Map<String, Object>> rows = new ArrayList<>();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT id, aggregate_type, aggregate_id, event_type, schema_version, payload, "
								+ "occurred_at, published_at FROM outbox_event WHERE aggregate_id = ?")) {
			statement.setObject(1, aggregateId);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					Map<String, Object> row = new java.util.LinkedHashMap<>();
					row.put("id", resultSet.getObject("id"));
					row.put("aggregate_type", resultSet.getString("aggregate_type"));
					row.put("aggregate_id", resultSet.getObject("aggregate_id"));
					row.put("event_type", resultSet.getString("event_type"));
					row.put("schema_version", resultSet.getInt("schema_version"));
					row.put("payload", resultSet.getString("payload"));
					Object occurredAt = resultSet.getObject("occurred_at", OffsetDateTime.class);
					row.put("occurred_at", occurredAt == null ? null : ((OffsetDateTime) occurredAt).toInstant());
					row.put("published_at", resultSet.getObject("published_at"));
					rows.add(row);
				}
			}
		}
		return rows;
	}

	private List<String> fieldNames(JsonNode node) {
		List<String> names = new ArrayList<>();
		node.fieldNames().forEachRemaining(names::add);
		return names;
	}

	private String validPayload() {
		return "{\"eventId\":\"" + UUID.randomUUID() + "\"}";
	}

	private String validPayloadForTransaction(UUID transactionId) {
		return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"transactionId\":\"" + transactionId + "\"}";
	}

	private void insertRawOutboxEvent(UUID aggregateId, String aggregateType, String eventType, String payload)
			throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"INSERT INTO outbox_event (id, aggregate_type, aggregate_id, event_type, schema_version, "
								+ "payload, occurred_at) VALUES (?, ?, ?, ?, 1, ?::jsonb, now())")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setString(2, aggregateType);
			statement.setObject(3, aggregateId);
			statement.setString(4, eventType);
			statement.setString(5, payload);
			statement.executeUpdate();
		}
	}

	private void assertThatSqlFails(SqlAction action, String messageFragment) {
		try {
			action.run();
			throw new AssertionError("Expected a SQLException" + (messageFragment != null
					? " containing '" + messageFragment + "'" : ""));
		}
		catch (SQLException expected) {
			if (messageFragment != null) {
				assertThat(expected.getMessage()).containsIgnoringCase(messageFragment);
			}
		}
	}

	private void assertThatSqlFails(PreparedStatement statement, String messageFragment) {
		assertThatSqlFails(statement::executeUpdate, messageFragment);
	}

	@FunctionalInterface
	private interface SqlAction {
		void run() throws SQLException;
	}

	private Set<String> constraintNames(String tableName) throws SQLException {
		Set<String> names = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT conname FROM pg_constraint WHERE conrelid = ?::regclass")) {
			statement.setString(1, tableName);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					names.add(resultSet.getString("conname"));
				}
			}
		}
		return names;
	}

	private Set<String> indexNames(String tableName) throws SQLException {
		Set<String> names = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT indexname FROM pg_indexes WHERE tablename = ?")) {
			statement.setString(1, tableName);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					names.add(resultSet.getString("indexname"));
				}
			}
		}
		return names;
	}

	private Set<String> triggerNames(String tableName) throws SQLException {
		Set<String> names = new HashSet<>();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT tgname FROM pg_trigger WHERE tgrelid = ?::regclass AND NOT tgisinternal")) {
			statement.setString(1, tableName);
			try (ResultSet resultSet = statement.executeQuery()) {
				while (resultSet.next()) {
					names.add(resultSet.getString("tgname"));
				}
			}
		}
		return names;
	}

}
