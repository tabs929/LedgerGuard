package com.tarun.ledgerguard;

import com.tarun.ledgerguard.security.TestAuthSupport;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task 16: a single bounded, mixed concurrent workload (deposits and
 * transfers, including opposite-direction transfer pairs, racing on a
 * small shared set of accounts) followed by a global consistency audit
 * queried directly from PostgreSQL — not from HTTP responses. Every
 * expected value the audit checks against is itself derived from the
 * database rows this run actually committed, never predicted or assumed
 * from what was submitted; in particular this test does not assume every
 * submitted transfer succeeds (an ordinary 422 insufficient-funds
 * response is accepted, and the audit is unconditionally correct either
 * way, since it only ever trusts committed ledger_entry rows).
 *
 * <p>Complements, rather than duplicates, the existing focused
 * concurrency tests in {@code DepositIntegrationTest} and
 * {@code TransferIntegrationTest} (same account, same source, opposite
 * direction) — this is the one place a *mixed* workload across *several*
 * shared accounts is exercised together, and the one place every table's
 * global invariants are checked in the same run.
 */
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MixedWorkloadConsistencyIntegrationTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16.4");

	@Autowired
	TestRestTemplate restTemplate;

	@Autowired
	DataSource dataSource;

	@Test
	void mixedConcurrentWorkloadPreservesAllInvariants() throws Exception {
		// One test-customer-a token, fetched once and reused for every
		// request below (including the concurrent phase) -- all four
		// accounts belong to the same principal, so ownership checks pass
		// uniformly and no request races the token endpoint itself.
		String token = TestAuthSupport.customerAToken(restTemplate);

		UUID accountA = createUsdCustomerAccount("Mixed Workload A", token);
		UUID accountB = createUsdCustomerAccount("Mixed Workload B", token);
		UUID accountC = createUsdCustomerAccount("Mixed Workload C", token);
		UUID accountD = createUsdCustomerAccount("Mixed Workload D", token);
		List<UUID> customerAccountIds = List.of(accountA, accountB, accountC, accountD);

		// Sequential seeding (not part of the concurrent phase) -- large
		// enough that no plausible interleaving of the small concurrent
		// transfers below could ever produce insufficient funds; this
		// keeps the workload's outcome close to deterministic without the
		// audit itself depending on that.
		for (UUID accountId : customerAccountIds) {
			ResponseEntity<Map> seed = postDeposit(accountId, "500.00", UUID.randomUUID().toString(), token);
			assertThat(seed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		}

		List<Callable<ResponseEntity<Map>>> tasks = new ArrayList<>();
		// Six concurrent deposits across the four accounts.
		String[] depositAmounts = { "5.00", "6.00", "7.00", "8.00", "9.00", "11.00" };
		for (int i = 0; i < depositAmounts.length; i++) {
			UUID target = customerAccountIds.get(i % customerAccountIds.size());
			String amount = depositAmounts[i];
			tasks.add(() -> postDeposit(target, amount, UUID.randomUUID().toString(), token));
		}
		// Eight concurrent transfers, including opposite-direction pairs
		// between the same two accounts and across different pairs --
		// each with its own distinct idempotency key (idempotency racing
		// itself is covered separately in IdempotencyIntegrationTest).
		UUID[][] transferPairs = {
				{ accountA, accountB }, { accountB, accountA },
				{ accountC, accountD }, { accountD, accountC },
				{ accountA, accountC }, { accountC, accountA },
				{ accountB, accountD }, { accountD, accountB }
		};
		for (UUID[] pair : transferPairs) {
			UUID source = pair[0];
			UUID destination = pair[1];
			tasks.add(() -> postTransfer(source, destination, "10.00", UUID.randomUUID().toString(), token));
		}

		int taskCount = tasks.size();
		CountDownLatch ready = new CountDownLatch(taskCount);
		CountDownLatch start = new CountDownLatch(1);
		List<Callable<ResponseEntity<Map>>> gatedTasks = new ArrayList<>();
		for (Callable<ResponseEntity<Map>> task : tasks) {
			gatedTasks.add(() -> {
				ready.countDown();
				start.await(10, TimeUnit.SECONDS);
				return task.call();
			});
		}

		ExecutorService executor = Executors.newFixedThreadPool(taskCount);
		List<ResponseEntity<Map>> results = new ArrayList<>();
		try {
			List<Future<ResponseEntity<Map>>> futures = executor.invokeAll(gatedTasks, 60, TimeUnit.SECONDS);
			ready.await(10, TimeUnit.SECONDS);
			start.countDown();
			for (Future<ResponseEntity<Map>> future : futures) {
				results.add(future.get(10, TimeUnit.SECONDS));
			}
		}
		finally {
			executor.shutdownNow();
		}

		// Every response is an ordinary, documented outcome -- never an
		// unhandled error, and never left unresolved. A 422 (insufficient
		// funds) is accepted as a normal outcome, not a failure -- this
		// test does not assume every submitted transfer succeeds.
		assertThat(results).hasSize(taskCount);
		for (ResponseEntity<Map> result : results) {
			assertThat(result.getStatusCode()).isIn(HttpStatus.CREATED, HttpStatus.UNPROCESSABLE_CONTENT);
		}
		long committedCount = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
		assertThat(committedCount).isGreaterThan(0);

		// ------------------------------------------------------------------
		// global consistency audit -- queried directly from PostgreSQL,
		// never from the HTTP responses above.
		// ------------------------------------------------------------------

		// H.1/H.2: every ledger_transaction has exactly two entries, and
		// debits equal credits per transaction (BigDecimal compareTo,
		// never floating point).
		assertThat(transactionsWithWrongEntryCount()).isEmpty();
		assertThat(transactionsWithUnbalancedEntries()).isEmpty();

		// H.3: every account's materialized balance equals its own
		// ledger-derived balance, using the real per-class formula
		// (ASSET: debits - credits; LIABILITY: credits - debits) --
		// derived purely from this run's own committed ledger_entry rows,
		// never predicted from what was submitted.
		UUID fundingAccountId = fetchUsdFundingAccountId();
		assertThat(fetchLedgerDerivedBalance(fundingAccountId, "ASSET")).isEqualByComparingTo(fetchBalance(fundingAccountId));
		for (UUID accountId : customerAccountIds) {
			assertThat(fetchLedgerDerivedBalance(accountId, "LIABILITY")).isEqualByComparingTo(fetchBalance(accountId));
		}

		// H.4: no negative balance anywhere (also a DB CHECK constraint --
		// this is a direct, positive confirmation against this run's data).
		assertThat(countRows("SELECT COUNT(*) FROM account WHERE balance < 0")).isZero();

		// H.5: no orphaned ledger_entry -- every account_id/transaction_id
		// resolves to a real row (also FK-enforced; confirmed directly).
		assertThat(countRows(
				"SELECT COUNT(*) FROM ledger_entry le WHERE NOT EXISTS "
						+ "(SELECT 1 FROM account a WHERE a.id = le.account_id) "
						+ "OR NOT EXISTS (SELECT 1 FROM ledger_transaction lt WHERE lt.id = le.transaction_id)"))
				.isZero();

		// H.6: no orphaned outbox_event/idempotency_key reference.
		// outbox_event.aggregate_id -> ledger_transaction.id and
		// idempotency_key.ledger_transaction_id -> ledger_transaction.id
		// are both real foreign keys declared in V3/V2 respectively (not
		// assumed) -- and, per Task 11/10, every committed ledger
		// transaction in this application gets exactly one outbox_event
		// and was claimed by exactly one idempotency_key (every call
		// above used its own distinct key), so the two counts must match
		// the transaction count exactly, not just be non-orphaned.
		assertThat(countRows(
				"SELECT COUNT(*) FROM outbox_event oe WHERE NOT EXISTS "
						+ "(SELECT 1 FROM ledger_transaction lt WHERE lt.id = oe.aggregate_id)")).isZero();
		assertThat(countRows(
				"SELECT COUNT(*) FROM idempotency_key ik WHERE NOT EXISTS "
						+ "(SELECT 1 FROM ledger_transaction lt WHERE lt.id = ik.ledger_transaction_id)")).isZero();
		long transactionCount = countRows("SELECT COUNT(*) FROM ledger_transaction");
		assertThat(countRows("SELECT COUNT(*) FROM outbox_event")).isEqualTo(transactionCount);
		assertThat(countRows("SELECT COUNT(*) FROM idempotency_key")).isEqualTo(transactionCount);

		// H.7 (spot check only -- SchemaMigrationIntegrationTest already
		// exhaustively proves every immutability trigger; this confirms
		// it still rejects a mutation against a row this workload itself
		// just committed, not stale/pre-existing data).
		assertUpdateOnLedgerEntryStillRejected();
	}

	// ------------------------------------------------------------------
	// audit query helpers
	// ------------------------------------------------------------------

	private List<UUID> transactionsWithWrongEntryCount() throws SQLException {
		List<UUID> offenders = new ArrayList<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT lt.id FROM ledger_transaction lt "
								+ "JOIN ledger_entry le ON le.transaction_id = lt.id "
								+ "GROUP BY lt.id HAVING COUNT(*) <> 2")) {
			while (resultSet.next()) {
				offenders.add((UUID) resultSet.getObject("id"));
			}
		}
		return offenders;
	}

	private List<UUID> transactionsWithUnbalancedEntries() throws SQLException {
		List<UUID> offenders = new ArrayList<>();
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT lt.id, "
								+ "  SUM(CASE WHEN le.entry_type = 'DEBIT' THEN le.amount ELSE 0 END) AS total_debit, "
								+ "  SUM(CASE WHEN le.entry_type = 'CREDIT' THEN le.amount ELSE 0 END) AS total_credit "
								+ "FROM ledger_transaction lt "
								+ "JOIN ledger_entry le ON le.transaction_id = lt.id "
								+ "GROUP BY lt.id")) {
			while (resultSet.next()) {
				BigDecimal debit = resultSet.getBigDecimal("total_debit");
				BigDecimal credit = resultSet.getBigDecimal("total_credit");
				if (debit.compareTo(credit) != 0) {
					offenders.add((UUID) resultSet.getObject("id"));
				}
			}
		}
		return offenders;
	}

	/**
	 * Recomputes one account's balance directly from its own immutable
	 * ledger_entry rows using the real per-account-class formula (per
	 * docs/DATA_MODEL.md): ASSET accounts are debits minus credits,
	 * LIABILITY accounts are credits minus debits. Never trusts the
	 * materialized account.balance column.
	 */
	private BigDecimal fetchLedgerDerivedBalance(UUID accountId, String accountClass) throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT COALESCE(SUM(CASE WHEN entry_type = 'DEBIT' THEN amount ELSE 0 END), 0) AS total_debit, "
								+ "COALESCE(SUM(CASE WHEN entry_type = 'CREDIT' THEN amount ELSE 0 END), 0) AS total_credit "
								+ "FROM ledger_entry WHERE account_id = ?")) {
			statement.setObject(1, accountId);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				BigDecimal debit = resultSet.getBigDecimal("total_debit");
				BigDecimal credit = resultSet.getBigDecimal("total_credit");
				return "ASSET".equals(accountClass) ? debit.subtract(credit) : credit.subtract(debit);
			}
		}
	}

	private void assertUpdateOnLedgerEntryStillRejected() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				PreparedStatement selectAny = connection.prepareStatement("SELECT id FROM ledger_entry LIMIT 1");
				ResultSet resultSet = selectAny.executeQuery()) {
			assertThat(resultSet.next()).isTrue();
			UUID anyEntryId = (UUID) resultSet.getObject("id");
			try (PreparedStatement update = connection.prepareStatement(
					"UPDATE ledger_entry SET amount = 999.0000 WHERE id = ?")) {
				update.setObject(1, anyEntryId);
				assertThatThrownBy(update::executeUpdate).isInstanceOf(SQLException.class)
						.hasMessageContaining("immutable");
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

	// ------------------------------------------------------------------
	// HTTP helpers
	// ------------------------------------------------------------------

	private UUID createUsdCustomerAccount(String ownerName, String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(token);
		ResponseEntity<Map> response = restTemplate.postForEntity(
				"/api/v1/accounts", new HttpEntity<>(Map.of("ownerName", ownerName, "currency", "USD"), headers),
				Map.class);
		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		return UUID.fromString((String) response.getBody().get("id"));
	}

	private ResponseEntity<Map> postDeposit(UUID accountId, String amount, String idempotencyKey, String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(token);
		headers.set("Idempotency-Key", idempotencyKey);
		return restTemplate.postForEntity("/api/v1/accounts/" + accountId + "/deposits",
				new HttpEntity<>(Map.of("amount", amount, "currency", "USD"), headers), Map.class);
	}

	private ResponseEntity<Map> postTransfer(UUID sourceId, UUID destinationId, String amount, String idempotencyKey,
			String token) {
		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_JSON);
		headers.setBearerAuth(token);
		headers.set("Idempotency-Key", idempotencyKey);
		Map<String, Object> body = Map.of("sourceAccountId", sourceId.toString(),
				"destinationAccountId", destinationId.toString(), "amount", amount, "currency", "USD");
		return restTemplate.postForEntity("/api/v1/transfers", new HttpEntity<>(body, headers), Map.class);
	}

	private UUID fetchUsdFundingAccountId() throws SQLException {
		try (Connection connection = dataSource.getConnection();
				Statement statement = connection.createStatement();
				ResultSet resultSet = statement.executeQuery(
						"SELECT id FROM account WHERE account_category = 'SYSTEM' "
								+ "AND account_purpose = 'EXTERNAL_FUNDING' AND currency = 'USD'")) {
			assertThat(resultSet.next()).isTrue();
			return (UUID) resultSet.getObject("id");
		}
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

}
