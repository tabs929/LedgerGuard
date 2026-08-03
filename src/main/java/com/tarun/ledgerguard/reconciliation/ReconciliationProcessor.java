package com.tarun.ledgerguard.reconciliation;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Computes and atomically commits one reconciliation run — a separate
 * Spring bean from {@code ReconciliationService} specifically so this
 * class's {@code @Transactional} proxy boundary is effective
 * (self-invocation would silently bypass it, the same reason
 * {@code SettlementImportProcessor} and {@code LedgerEventProcessor} are
 * each separate beans from their own orchestrating service/listener).
 *
 * <p><b>READ COMMITTED, not REPEATABLE READ</b> (the isolation level is
 * explicit below even though it is PostgreSQL's own default, so this
 * choice cannot silently drift): under REPEATABLE READ, a transaction's
 * snapshot is fixed at its own start. A losing transaction that blocks on
 * the winner's uncommitted {@code reconciliation_run} row, then unblocks
 * once the winner commits, would still be looking through its own
 * *original* snapshot — taken before the winner existed — and could fail
 * to see the now-committed winning row at all. Under READ COMMITTED, the
 * follow-up {@code SELECT} after losing the claim gets a fresh snapshot
 * and reliably observes the winner's committed data. This exact
 * losing-side follow-up read is why READ COMMITTED is required here, not
 * merely permitted.
 *
 * <p>Never touches {@code account}, {@code ledger_entry},
 * {@code ledger_transaction}, {@code settlement_import},
 * {@code settlement_record}, {@code outbox_event}, {@code processed_event},
 * or {@code idempotency_key} — {@code reconciliation_run}/
 * {@code reconciliation_result} inserts are this method's entire effect.
 */
@Component
class ReconciliationProcessor {

	private final SettlementObservationRepository observationRepository;
	private final LedgerDataLoader ledgerDataLoader;
	private final ReconciliationRunRepository runRepository;
	private final ReconciliationResultRepository resultRepository;

	ReconciliationProcessor(SettlementObservationRepository observationRepository, LedgerDataLoader ledgerDataLoader,
			ReconciliationRunRepository runRepository, ReconciliationResultRepository resultRepository) {
		this.observationRepository = observationRepository;
		this.ledgerDataLoader = ledgerDataLoader;
		this.runRepository = runRepository;
		this.resultRepository = resultRepository;
	}

	@Transactional(isolation = Isolation.READ_COMMITTED)
	ReconciliationRunOutcome reconcile(UUID settlementImportId, int algorithmVersion) {
		// Step 1: compute the complete proposed result set before
		// claiming anything -- settlement_record and ledger data are both
		// immutable, so reading them first (rather than inside a lock) is
		// safe and lets the final run-row counts be known before it is
		// ever inserted (append-only: never placeholder counts updated
		// afterward).
		List<SettlementObservation> observations = observationRepository.findByFirstImportId(settlementImportId);

		Set<UUID> reportedTransactionIds = observations.stream()
				.map(SettlementObservation::reportedTransactionId)
				.collect(Collectors.toSet());
		Map<UUID, LedgerTransactionView> transactionsById = ledgerDataLoader.loadByTransactionIds(reportedTransactionIds);

		UUID runId = UUID.randomUUID();
		List<ReconciliationResultRepository.NewResult> proposedResults = new ArrayList<>(observations.size());
		int matchedCount = 0;
		int discrepancyCount = 0;
		int inconsistentCount = 0;

		for (SettlementObservation observation : observations) {
			LedgerTransactionView transactionView = transactionsById.get(observation.reportedTransactionId());
			ReconciliationClassification classification = ReconciliationMatcher.classify(observation, transactionView);

			switch (classification.outcome()) {
				case MATCHED -> matchedCount++;
				case INTERNAL_LEDGER_INCONSISTENT -> inconsistentCount++;
				default -> discrepancyCount++;
			}

			proposedResults.add(new ReconciliationResultRepository.NewResult(
					UUID.randomUUID(), runId, observation.settlementRecordId(), observation.reportedTransactionId(),
					classification.outcome(), observation.reportedAmount(), observation.reportedCurrency(),
					classification.internalAmount(), classification.internalCurrency()));
		}

		// Step 2: atomically claim (settlement_import_id, algorithm_version).
		Optional<StoredReconciliationRun> claimed = runRepository.tryInsert(runId, settlementImportId,
				algorithmVersion, observations.size(), matchedCount, discrepancyCount, inconsistentCount);

		if (claimed.isPresent()) {
			// Step 4 (won): insert every result in the same transaction.
			resultRepository.insertAll(proposedResults);
			return new ReconciliationRunOutcome(claimed.get(), false);
		}

		// Step 3 (lost): load and return the committed winning run as a
		// replay. No results are inserted on this path -- they already
		// exist, written by the winner.
		StoredReconciliationRun winner = runRepository
				.findBySettlementImportIdAndAlgorithmVersion(settlementImportId, algorithmVersion)
				.orElseThrow(() -> new IllegalStateException(
						"reconciliation_run claim conflicted but no existing row was found"));
		return new ReconciliationRunOutcome(winner, true);
	}

}
