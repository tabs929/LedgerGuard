package com.tarun.ledgerguard.reconciliation;

import com.tarun.ledgerguard.common.PagedResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Orchestrates the command and two read endpoints. Delegates the actual
 * transactional compute-and-commit work to {@link ReconciliationProcessor};
 * this class opens no database transaction of its own beyond the plain
 * reads its repositories perform.
 *
 * <p>Task 15 always uses algorithm version 1 — there is no public API to
 * select a different version (see docs/ARCHITECTURE.md).
 */
@Service
public class ReconciliationService {

	private static final int ALGORITHM_VERSION = 1;

	private final SettlementImportSummaryRepository importSummaryRepository;
	private final ReconciliationProcessor processor;
	private final ReconciliationRunRepository runRepository;
	private final ReconciliationResultRepository resultRepository;

	public ReconciliationService(SettlementImportSummaryRepository importSummaryRepository,
			ReconciliationProcessor processor, ReconciliationRunRepository runRepository,
			ReconciliationResultRepository resultRepository) {
		this.importSummaryRepository = importSummaryRepository;
		this.processor = processor;
		this.runRepository = runRepository;
		this.resultRepository = resultRepository;
	}

	public ReconciliationSummaryResponse reconcile(UUID importId) {
		SettlementImportSummary summary = requireImport(importId);
		ReconciliationRunOutcome outcome = processor.reconcile(importId, ALGORITHM_VERSION);
		return toSummaryResponse(outcome.run(), summary, outcome.replayed());
	}

	public ReconciliationSummaryResponse getRun(UUID importId) {
		SettlementImportSummary summary = requireImport(importId);
		StoredReconciliationRun run = requireRun(importId);
		return toSummaryResponse(run, summary, false);
	}

	public PagedResponse<ReconciliationResultResponse> getResults(UUID importId, int page, int size) {
		requireImport(importId);
		StoredReconciliationRun run = requireRun(importId);

		List<StoredReconciliationResult> results = resultRepository.findByRunId(run.id(), size, page * size);
		List<ReconciliationResultResponse> content = results.stream().map(this::toResultResponse).toList();

		int totalPages = (run.totalResultCount() + size - 1) / size;
		return new PagedResponse<>(content, page, size, run.totalResultCount(), totalPages);
	}

	private SettlementImportSummary requireImport(UUID importId) {
		return importSummaryRepository.findById(importId)
				.orElseThrow(() -> new SettlementImportNotFoundException(importId));
	}

	private StoredReconciliationRun requireRun(UUID importId) {
		return runRepository.findLatestBySettlementImportId(importId)
				.orElseThrow(() -> new ReconciliationNotFoundException(importId));
	}

	private ReconciliationSummaryResponse toSummaryResponse(StoredReconciliationRun run,
			SettlementImportSummary summary, boolean replayed) {
		return new ReconciliationSummaryResponse(
				run.id(),
				run.settlementImportId(),
				run.algorithmVersion(),
				summary.totalRowCount(),
				summary.insertedRowCount(),
				summary.duplicateRowCount(),
				run.totalResultCount(),
				run.matchedCount(),
				run.discrepancyCount(),
				run.inconsistentCount(),
				replayed,
				run.createdAt());
	}

	private ReconciliationResultResponse toResultResponse(StoredReconciliationResult result) {
		return new ReconciliationResultResponse(
				result.id(),
				result.settlementRecordId(),
				result.reportedTransactionId(),
				result.outcome(),
				result.reportedAmount(),
				result.reportedCurrency(),
				result.internalAmount(),
				result.internalCurrency(),
				result.createdAt());
	}

}
