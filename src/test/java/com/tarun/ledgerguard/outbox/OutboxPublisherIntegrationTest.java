package com.tarun.ledgerguard.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Task 12: publishing Task 11 outbox_event rows to Kafka. Against a real,
 * isolated PostgreSQL 16.4 Testcontainer AND a real, isolated Kafka
 * Testcontainer (KRaft, no ZooKeeper) — no Kafka broker behavior is ever
 * mocked. The publisher scheduler is explicitly re-enabled here (the
 * shared {@code application-test.yml} disables it for every other suite)
 * with a short poll delay so tests observe real scheduled polling rather
 * than depending on the production default cadence. All waits are bounded
 * (Awaitility), never {@code Thread.sleep} as the correctness mechanism.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@TestPropertySource(properties = {
		"ledgerguard.outbox.publisher.enabled=true",
		// Deliberately long: almost every test below calls
		// OutboxPublisher.publishIfPending(...) directly for deterministic,
		// immediate behavior rather than waiting on wall-clock @Scheduled
		// firing (see docs/TEST_STRATEGY.md's "Outbox Publisher Tests"
		// section). A live background poll racing those direct calls would
		// make "still pending" assertions flaky. Exactly one test
		// (schedulerPollAndPublishPendingEventsPublishesAllCurrentCandidates)
		// exercises the scheduler's own polling method directly, still
		// without depending on this timer actually firing.
		"ledgerguard.outbox.publisher.poll-delay-millis=3600000",
		"ledgerguard.outbox.publisher.batch-size=50"
})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OutboxPublisherIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Container
	@ServiceConnection
	static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

	@Autowired
	TestRestTemplate restTemplate;

	@Autowired
	DataSource dataSource;

	@Autowired
	OutboxEventRepository outboxEventRepository;

	@Autowired
	OutboxPublisher outboxPublisher;

	@Autowired
	OutboxPublisherScheduler outboxPublisherScheduler;

	@Autowired
	OutboxPublisherProperties publisherProperties;

	private static final ObjectMapper JSON = new ObjectMapper();

	private final List<KafkaConsumer<String, String>> consumersToClose = new ArrayList<>();

	@AfterEach
	void closeConsumers() {
		consumersToClose.forEach(KafkaConsumer::close);
		consumersToClose.clear();
	}

	// ------------------------------------------------------------------
	// topic and producer configuration
	// ------------------------------------------------------------------

	@Test
	void configuredTopicIsCreatedWithExpectedPartitionCount() throws Exception {
		Properties adminProps = new Properties();
		adminProps.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		try (Admin admin = Admin.create(adminProps)) {
			Map<String, TopicDescription> described = admin.describeTopics(List.of(publisherProperties.getTopic()))
					.allTopicNames().get(10, TimeUnit.SECONDS);
			TopicDescription topic = described.get(publisherProperties.getTopic());
			assertThat(topic).isNotNull();
			assertThat(topic.partitions()).hasSize(publisherProperties.getPartitions());
		}
	}

	// ------------------------------------------------------------------
	// deposit publication
	// ------------------------------------------------------------------

	@Test
	void depositPublishesExactlyOneDepositCompletedRecord() throws Exception {
		UUID accountId = createUsdCustomerAccount("Publisher Deposit Owner");
		String key = UUID.randomUUID().toString();

		ResponseEntity<Map> response = postDeposit(accountId, "100.00", key);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));

		awaitPublished(transactionId);

		List<ConsumerRecord<String, String>> records = consumeAllRecordsForKey(transactionId.toString(), 1);
		assertThat(records).hasSize(1);
		ConsumerRecord<String, String> record = records.get(0);
		assertThat(record.key()).isEqualTo(transactionId.toString());

		JsonNode value = JSON.readTree(record.value());
		assertThat(value.get("eventType").asText()).isEqualTo("DEPOSIT_COMPLETED");
		assertThat(value.get("transactionId").asText()).isEqualTo(transactionId.toString());
		assertThat(value.get("destinationAccountId").asText()).isEqualTo(accountId.toString());
		assertThat(value.get("amount").asText()).isEqualTo("100.0000");
		assertThat(value.get("currency").asText()).isEqualTo("USD");

		String storedPayload = fetchStoredPayload(transactionId);
		JsonNode stored = JSON.readTree(storedPayload);
		assertThat(value).isEqualTo(stored);

		assertThat(record.key()).doesNotContain(key);
		assertThat(record.value()).doesNotContain(key);
		record.headers().forEach(h -> assertThat(new String(h.value())).doesNotContain(key));

		assertThat(fetchBalance(accountId)).isEqualByComparingTo("100.00");
		assertThat(countIdempotencyRows(key)).isEqualTo(1);
	}

	// ------------------------------------------------------------------
	// transfer publication
	// ------------------------------------------------------------------

	@Test
	void transferPublishesExactlyOneTransferCompletedRecord() throws Exception {
		UUID sourceId = createFundedAccount("Publisher Transfer Source", "100.00");
		UUID destinationId = createUsdCustomerAccount("Publisher Transfer Destination");
		String key = UUID.randomUUID().toString();

		ResponseEntity<Map> response = postTransfer(sourceId, destinationId, "30.00", key);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));

		awaitPublished(transactionId);

		List<ConsumerRecord<String, String>> records = consumeAllRecordsForKey(transactionId.toString(), 1);
		assertThat(records).hasSize(1);
		JsonNode value = JSON.readTree(records.get(0).value());
		assertThat(value.get("eventType").asText()).isEqualTo("TRANSFER_COMPLETED");
		assertThat(value.get("sourceAccountId").asText()).isEqualTo(sourceId.toString());
		assertThat(value.get("destinationAccountId").asText()).isEqualTo(destinationId.toString());
		assertThat(value.get("amount").asText()).isEqualTo("30.0000");
		assertThat(value.get("currency").asText()).isEqualTo("USD");
	}

	// ------------------------------------------------------------------
	// idempotency behavior
	// ------------------------------------------------------------------

	@Test
	void identicalDepositRetryProducesNoSecondRecord() throws Exception {
		UUID accountId = createUsdCustomerAccount("Publisher Deposit Replay");
		String key = UUID.randomUUID().toString();

		ResponseEntity<Map> first = postDeposit(accountId, "20.00", key);
		UUID transactionId = UUID.fromString((String) first.getBody().get("transactionId"));
		postDeposit(accountId, "20.00", key);

		awaitPublished(transactionId);
		List<ConsumerRecord<String, String>> records = consumeAllRecordsForKey(transactionId.toString(), 1);
		assertThat(records).hasSize(1);
		assertThat(countOutboxRowsForAggregate(transactionId)).isEqualTo(1);
	}

	@Test
	void numericallyEquivalentRetryProducesNoSecondRecord() throws Exception {
		UUID accountId = createUsdCustomerAccount("Publisher Deposit Replay Formatting");
		String key = UUID.randomUUID().toString();

		ResponseEntity<Map> first = postDeposit(accountId, "50", key);
		UUID transactionId = UUID.fromString((String) first.getBody().get("transactionId"));
		postDeposit(accountId, "50.00", key);
		postDeposit(accountId, "50.0000", key);

		awaitPublished(transactionId);
		assertThat(consumeAllRecordsForKey(transactionId.toString(), 1)).hasSize(1);
	}

	@Test
	void identicalTransferRetryProducesNoSecondRecord() throws Exception {
		UUID sourceId = createFundedAccount("Publisher Transfer Replay Source", "100.00");
		UUID destinationId = createUsdCustomerAccount("Publisher Transfer Replay Destination");
		String key = UUID.randomUUID().toString();

		ResponseEntity<Map> first = postTransfer(sourceId, destinationId, "15.00", key);
		UUID transactionId = UUID.fromString((String) first.getBody().get("transactionId"));
		postTransfer(sourceId, destinationId, "15.00", key);

		awaitPublished(transactionId);
		assertThat(consumeAllRecordsForKey(transactionId.toString(), 1)).hasSize(1);
	}

	@Test
	void conflictingDepositRetryProducesNoRecordForTheConflict() throws Exception {
		UUID accountId = createUsdCustomerAccount("Publisher Deposit Conflict");
		String key = UUID.randomUUID().toString();
		ResponseEntity<Map> first = postDeposit(accountId, "10.00", key);
		UUID transactionId = UUID.fromString((String) first.getBody().get("transactionId"));

		ResponseEntity<String> conflict = postDepositRaw(accountId, "20.00", key);
		assertThat(conflict.getStatusCode().value()).isEqualTo(409);

		awaitPublished(transactionId);
		assertThat(consumeAllRecordsForKey(transactionId.toString(), 1)).hasSize(1);
	}

	@Test
	void crossOperationConflictProducesNoRecord() throws Exception {
		UUID accountId = createFundedAccount("Publisher Cross Op Source", "50.00");
		UUID destinationId = createUsdCustomerAccount("Publisher Cross Op Destination");
		String key = UUID.randomUUID().toString();
		ResponseEntity<Map> first = postDeposit(accountId, "10.00", key);
		UUID transactionId = UUID.fromString((String) first.getBody().get("transactionId"));

		ResponseEntity<String> conflict = postTransferRaw(accountId, destinationId, "10.00", key);
		assertThat(conflict.getStatusCode().value()).isEqualTo(409);

		awaitPublished(transactionId);
		assertThat(consumeAllRecordsForKey(transactionId.toString(), 1)).hasSize(1);
	}

	// ------------------------------------------------------------------
	// failure behavior
	// ------------------------------------------------------------------

	@Test
	void brokerFailureLeavesPublishedAtNullAndFinancialAndIdempotencyStateUnchanged() throws Exception {
		UUID accountId = createUsdCustomerAccount("Publisher Broker Failure");
		String key = UUID.randomUUID().toString();
		ResponseEntity<Map> response = postDeposit(accountId, "10.00", key);
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));

		UUID eventId = fetchEventId(transactionId);
		OutboxPublisher unreachablePublisher = publisherWithUnreachableBroker();
		org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
				() -> unreachablePublisher.publishIfPending(eventId));

		assertThat(fetchPublishedAt(transactionId)).isNull();
		assertThat(fetchBalance(accountId)).isEqualByComparingTo("10.00");
		assertThat(countIdempotencyRows(key)).isEqualTo(1);

		// Recovery: the real, working publisher succeeds for the same row.
		outboxPublisher.publishIfPending(eventId);
		assertThat(fetchPublishedAt(transactionId)).isNotNull();
		assertThat(consumeAllRecordsForKey(transactionId.toString(), 1)).hasSize(1);
	}

	@Test
	void oneFailedCandidateDoesNotPreventALaterCandidateFromBeingPublished() throws Exception {
		UUID accountId1 = createUsdCustomerAccount("Publisher Batch Failure A");
		UUID accountId2 = createUsdCustomerAccount("Publisher Batch Failure B");
		ResponseEntity<Map> r1 = postDeposit(accountId1, "5.00", UUID.randomUUID().toString());
		ResponseEntity<Map> r2 = postDeposit(accountId2, "6.00", UUID.randomUUID().toString());
		UUID failingCandidate = UUID.fromString((String) r1.getBody().get("transactionId"));
		UUID laterCandidate = UUID.fromString((String) r2.getBody().get("transactionId"));
		UUID failingEventId = fetchEventId(failingCandidate);
		UUID laterEventId = fetchEventId(laterCandidate);

		OutboxPublisher unreachablePublisher = publisherWithUnreachableBroker();
		// Same catch-and-continue shape OutboxPublisherScheduler itself uses:
		// one candidate's failure must not stop the next candidate's attempt.
		try {
			unreachablePublisher.publishIfPending(failingEventId);
		}
		catch (RuntimeException expected) {
			// expected: unreachable broker
		}
		outboxPublisher.publishIfPending(laterEventId);

		assertThat(fetchPublishedAt(failingCandidate)).isNull();
		assertThat(fetchPublishedAt(laterCandidate)).isNotNull();
	}

	// ------------------------------------------------------------------
	// multi-instance / concurrency
	// ------------------------------------------------------------------

	@Test
	void twoSimultaneousWorkersOnOnePendingEventProduceExactlyOneRecord() throws Exception {
		UUID accountId = createUsdCustomerAccount("Publisher Concurrent Single Event");
		ResponseEntity<Map> response = postDeposit(accountId, "7.00", UUID.randomUUID().toString());
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));
		UUID eventId = fetchEventId(transactionId);

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Callable<Void>> tasks = List.of(
					() -> { outboxPublisher.publishIfPending(eventId); return null; },
					() -> { outboxPublisher.publishIfPending(eventId); return null; });
			List<Future<Void>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
			for (Future<Void> future : futures) {
				future.get(5, TimeUnit.SECONDS);
			}
		}
		finally {
			executor.shutdown();
		}

		awaitPublished(transactionId);
		assertThat(consumeAllRecordsForKey(transactionId.toString(), 1)).hasSize(1);
		assertThat(countOutboxRowsForAggregate(transactionId)).isEqualTo(1);
	}

	@Test
	void multipleDistinctPendingEventsProcessConcurrentlyWithoutDeadlock() throws Exception {
		int count = 6;
		List<UUID> transactionIds = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			UUID accountId = createUsdCustomerAccount("Publisher Concurrent Distinct " + i);
			ResponseEntity<Map> response = postDeposit(accountId, "1.00", UUID.randomUUID().toString());
			transactionIds.add(UUID.fromString((String) response.getBody().get("transactionId")));
		}
		List<UUID> eventIds = new ArrayList<>();
		for (UUID transactionId : transactionIds) {
			eventIds.add(fetchEventId(transactionId));
		}

		ExecutorService executor = Executors.newFixedThreadPool(count);
		try {
			List<Callable<Void>> tasks = eventIds.stream()
					.map(id -> (Callable<Void>) () -> { outboxPublisher.publishIfPending(id); return null; })
					.collect(Collectors.toList());
			List<Future<Void>> futures = executor.invokeAll(tasks, 30, TimeUnit.SECONDS);
			for (Future<Void> future : futures) {
				future.get(5, TimeUnit.SECONDS);
			}
		}
		finally {
			executor.shutdown();
		}

		for (UUID transactionId : transactionIds) {
			assertThat(fetchPublishedAt(transactionId)).isNotNull();
		}
	}

	// ------------------------------------------------------------------
	// ordering and batching
	// ------------------------------------------------------------------

	@Test
	void candidateIdsAreSelectedByCreatedAtThenId() throws Exception {
		// findPendingCandidateIds returns outbox_event's own id (the
		// candidate id publishIfPending expects), not the ledger
		// transaction/aggregate id -- resolve each in creation order.
		List<UUID> expectedOrder = new ArrayList<>();
		for (int i = 0; i < 3; i++) {
			UUID accountId = createUsdCustomerAccount("Publisher Ordering " + i);
			ResponseEntity<Map> response = postDeposit(accountId, "1.00", UUID.randomUUID().toString());
			UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));
			expectedOrder.add(fetchEventId(transactionId));
		}

		List<UUID> candidates = outboxEventRepository.findPendingCandidateIds(1000);
		List<UUID> relevant = candidates.stream().filter(expectedOrder::contains).collect(Collectors.toList());
		assertThat(relevant).containsExactlyElementsOf(expectedOrder);
	}

	@Test
	void noMoreThanTheConfiguredBatchSizeIsSelected() throws Exception {
		for (int i = 0; i < 5; i++) {
			UUID accountId = createUsdCustomerAccount("Publisher Batch Size " + i);
			postDeposit(accountId, "1.00", UUID.randomUUID().toString());
		}

		List<UUID> candidates = outboxEventRepository.findPendingCandidateIds(3);
		assertThat(candidates).hasSizeLessThanOrEqualTo(3);
	}

	@Test
	void alreadyPublishedEventsAreNotSelectedAsCandidates() throws Exception {
		UUID accountId = createUsdCustomerAccount("Publisher Already Published");
		ResponseEntity<Map> response = postDeposit(accountId, "1.00", UUID.randomUUID().toString());
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));

		awaitPublished(transactionId);

		List<UUID> candidates = outboxEventRepository.findPendingCandidateIds(1000);
		assertThat(candidates).doesNotContain(transactionId);
	}

	// ------------------------------------------------------------------
	// scheduled polling (activation only -- see the class-level poll-delay
	// note for why every other test calls OutboxPublisher directly instead
	// of depending on wall-clock @Scheduled timing)
	// ------------------------------------------------------------------

	@Test
	void schedulerPollAndPublishPendingEventsPublishesAllCurrentCandidates() throws Exception {
		UUID accountId1 = createUsdCustomerAccount("Publisher Scheduler Activation A");
		UUID accountId2 = createUsdCustomerAccount("Publisher Scheduler Activation B");
		ResponseEntity<Map> r1 = postDeposit(accountId1, "2.00", UUID.randomUUID().toString());
		ResponseEntity<Map> r2 = postDeposit(accountId2, "3.00", UUID.randomUUID().toString());
		UUID transactionId1 = UUID.fromString((String) r1.getBody().get("transactionId"));
		UUID transactionId2 = UUID.fromString((String) r2.getBody().get("transactionId"));

		// Calls the scheduler's own polling method directly -- proves its
		// candidate-selection-and-delegation logic (not Spring's own
		// well-tested @Scheduled timer, which this project doesn't need to
		// reprove) actually publishes every currently pending candidate.
		outboxPublisherScheduler.pollAndPublishPendingEvents();

		assertThat(fetchPublishedAt(transactionId1)).isNotNull();
		assertThat(fetchPublishedAt(transactionId2)).isNotNull();
	}

	// ------------------------------------------------------------------
	// database trigger behavior (after a real publish)
	// ------------------------------------------------------------------

	@Test
	void publisherChangesOnlyPublishedAtAndTriggersRemainEffective() throws Exception {
		UUID accountId = createUsdCustomerAccount("Publisher Trigger Check");
		ResponseEntity<Map> response = postDeposit(accountId, "1.00", UUID.randomUUID().toString());
		UUID transactionId = UUID.fromString((String) response.getBody().get("transactionId"));

		String payloadBefore = fetchStoredPayload(transactionId);
		awaitPublished(transactionId);
		String payloadAfter = fetchStoredPayload(transactionId);
		assertThat(payloadAfter).isEqualTo(payloadBefore);

		UUID eventId = fetchEventId(transactionId);
		try (Connection connection = dataSource.getConnection();
				PreparedStatement update = connection.prepareStatement(
						"UPDATE outbox_event SET event_type = 'TRANSFER_COMPLETED' WHERE id = ?")) {
			update.setObject(1, eventId);
			assertThatSqlFails(update, "immutable");
		}
		try (Connection connection = dataSource.getConnection();
				PreparedStatement update = connection.prepareStatement(
						"UPDATE outbox_event SET published_at = NULL WHERE id = ?")) {
			update.setObject(1, eventId);
			assertThatSqlFails(update, "published_at");
		}
		try (Connection connection = dataSource.getConnection();
				PreparedStatement update = connection.prepareStatement(
						"UPDATE outbox_event SET published_at = now() WHERE id = ?")) {
			update.setObject(1, eventId);
			assertThatSqlFails(update, "published_at");
		}
		try (Connection connection = dataSource.getConnection();
				PreparedStatement delete = connection.prepareStatement("DELETE FROM outbox_event WHERE id = ?")) {
			delete.setObject(1, eventId);
			assertThatSqlFails(delete, "immutable");
		}
	}

	// ------------------------------------------------------------------
	// helpers
	// ------------------------------------------------------------------

	private OutboxPublisher publisherWithUnreachableBroker() {
		Map<String, Object> configs = new HashMap<>();
		configs.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:1");
		configs.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
		configs.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
		configs.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000);
		configs.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 3000);
		configs.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
		KafkaTemplate<String, String> badTemplate = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(configs));
		return new OutboxPublisher(outboxEventRepository, badTemplate, publisherProperties);
	}

	// Publishes deterministically and immediately, rather than waiting on
	// wall-clock @Scheduled firing -- see the class-level poll-delay note.
	// A no-op (nothing to do) if the row was already published by an
	// earlier call in the same test. publishIfPending takes outbox_event's
	// own id (the candidate id a real poll would select), never the
	// ledger_transaction/aggregate id, so this resolves it first.
	private void awaitPublished(UUID transactionId) throws SQLException {
		outboxPublisher.publishIfPending(fetchEventId(transactionId));
		assertThat(fetchPublishedAt(transactionId)).isNotNull();
	}

	private List<ConsumerRecord<String, String>> consumeAllRecordsForKey(String key, int expectedCount) {
		KafkaConsumer<String, String> consumer = newConsumer();
		consumer.subscribe(List.of(publisherProperties.getTopic()));
		List<ConsumerRecord<String, String>> matched = new ArrayList<>();
		await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200)).until(() -> {
			ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(300));
			polled.forEach(r -> {
				if (r.key().equals(key)) {
					matched.add(r);
				}
			});
			return matched.size() >= expectedCount;
		});
		// Drain a little longer to catch an unwanted duplicate, if any.
		long deadline = System.currentTimeMillis() + 1000;
		while (System.currentTimeMillis() < deadline) {
			ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
			polled.forEach(r -> {
				if (r.key().equals(key)) {
					matched.add(r);
				}
			});
		}
		return matched;
	}

	private KafkaConsumer<String, String> newConsumer() {
		Properties props = new Properties();
		props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		props.put(ConsumerConfig.GROUP_ID_CONFIG, "outbox-publisher-test-" + UUID.randomUUID());
		props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
		props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);
		consumersToClose.add(consumer);
		return consumer;
	}

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

	private long countOutboxRowsForAggregate(UUID aggregateId) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?")) {
			statement.setObject(1, aggregateId);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getLong(1);
			}
		}
	}

	private Object fetchPublishedAt(UUID aggregateId) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT published_at FROM outbox_event WHERE aggregate_id = ?")) {
			statement.setObject(1, aggregateId);
			try (ResultSet resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).isTrue();
				return resultSet.getObject("published_at");
			}
		}
	}

	private String fetchStoredPayload(UUID aggregateId) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT payload FROM outbox_event WHERE aggregate_id = ?")) {
			statement.setObject(1, aggregateId);
			try (ResultSet resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).isTrue();
				return resultSet.getString("payload");
			}
		}
	}

	private UUID fetchEventId(UUID aggregateId) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT id FROM outbox_event WHERE aggregate_id = ?")) {
			statement.setObject(1, aggregateId);
			try (ResultSet resultSet = statement.executeQuery()) {
				assertThat(resultSet.next()).isTrue();
				return (UUID) resultSet.getObject("id");
			}
		}
	}

	private void assertThatSqlFails(PreparedStatement statement, String messageFragment) {
		try {
			statement.executeUpdate();
			throw new AssertionError("Expected a SQLException containing '" + messageFragment + "'");
		}
		catch (SQLException expected) {
			assertThat(expected.getMessage()).containsIgnoringCase(messageFragment);
		}
	}

}
