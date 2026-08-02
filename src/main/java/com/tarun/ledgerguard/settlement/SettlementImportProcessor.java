package com.tarun.ledgerguard.settlement;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Performs the whole-file atomic import: claims the file identity, claims
 * every row identity, and persists the final {@code settlement_import}
 * row -- all inside one PostgreSQL transaction. A separate Spring bean
 * from {@code SettlementImportService} specifically so this class's
 * {@code @Transactional} proxy boundary is effective (self-invocation
 * would silently bypass it -- the same reason {@code outbox.OutboxPublisher}
 * is a separate bean from {@code outbox.OutboxPublisherScheduler}, and
 * {@code inbox.LedgerEventProcessor} is separate from
 * {@code inbox.LedgerEventConsumer}).
 *
 * <p>This method never touches {@code account}, {@code ledger_entry},
 * {@code ledger_transaction}, {@code outbox_event}, {@code processed_event},
 * or {@code idempotency_key} -- {@code settlement_import} and
 * {@code settlement_record} inserts are its entire effect.
 */
@Component
class SettlementImportProcessor {

	private final SettlementImportRepository importRepository;
	private final SettlementRecordRepository recordRepository;

	SettlementImportProcessor(SettlementImportRepository importRepository, SettlementRecordRepository recordRepository) {
		this.importRepository = importRepository;
		this.recordRepository = recordRepository;
	}

	@Transactional
	SettlementImportOutcome importFile(String source, String normalizedSource, String originalFilename,
			String fileHash, long fileSizeBytes, List<SettlementCsvRow> rows) {

		// Fast-path replay check only -- NOT relied on for correctness
		// under concurrency. Two transactions racing on the exact same
		// (normalized_source, file_hash) can both see "not found" here;
		// what actually arbitrates the race is the atomic ON CONFLICT
		// claim on settlement_import below, at the end of this method.
		// This SELECT only short-circuits the common case of importing a
		// file whose earlier import is already committed and visible.
		Optional<StoredSettlementImport> alreadyCommitted =
				importRepository.findByNormalizedSourceAndFileHash(normalizedSource, fileHash);
		if (alreadyCommitted.isPresent()) {
			return new SettlementImportOutcome(alreadyCommitted.get(), true);
		}

		UUID importId = UUID.randomUUID();
		int insertedCount = 0;
		int duplicateCount = 0;

		for (SettlementCsvRow row : rows) {
			boolean claimed = recordRepository.tryClaim(UUID.randomUUID(), normalizedSource, row.externalReference(),
					row.transactionId(), row.amount(), row.currency(), row.settledAt(), row.rowHash(), importId,
					row.sourceRowNumber());
			if (claimed) {
				insertedCount++;
				continue;
			}

			StoredSettlementRecord existing = recordRepository
					.findByNormalizedSourceAndExternalReference(normalizedSource, row.externalReference())
					.orElseThrow(() -> new IllegalStateException(
							"settlement_record claim conflicted but no existing row was found"));
			if (existing.rowHash().equals(row.rowHash())) {
				// Identical redelivery of an already-recorded observation
				// (possibly from a different, byte-distinct file) -- a
				// genuine duplicate, not an error. Counted, not inserted.
				duplicateCount++;
				continue;
			}

			// Conflicting duplicate: the same (normalized_source,
			// external_reference) identity already exists with different
			// business content. Throwing here rolls back this entire
			// @Transactional method -- every settlement_record row
			// claimed earlier in this loop, and the settlement_import
			// row below (never reached) -- per the Task 14 contract:
			// reject the whole file, create nothing, leave the original
			// observation unchanged.
			throw new SettlementConflictException(row.sourceRowNumber());
		}

		// settlement_import is append-only, so its row is inserted only
		// now that every row has been claimed/classified and the final
		// counts are known -- never inserted first with placeholder
		// counts and updated afterward. The settlement_record rows above
		// already reference importId via first_import_id, which V5
		// declares DEFERRABLE INITIALLY DEFERRED specifically so that
		// forward reference remains valid until this transaction commits.
		Optional<StoredSettlementImport> inserted = importRepository.tryInsert(importId, source, normalizedSource,
				originalFilename, fileHash, fileSizeBytes, rows.size(), insertedCount, duplicateCount);
		if (inserted.isPresent()) {
			return new SettlementImportOutcome(inserted.get(), false);
		}

		// Lost the race for (normalized_source, file_hash) against a
		// concurrent transaction that committed first. PostgreSQL blocks
		// a conflicting INSERT ... ON CONFLICT against an uncommitted row
		// until the first transaction resolves, so for a truly identical
		// file every row claim above already resolved as an identical
		// duplicate against the winner's now-committed rows -- this
		// transaction persisted no new settlement_record rows. Return the
		// winner's committed result as a replay.
		StoredSettlementImport winner = importRepository
				.findByNormalizedSourceAndFileHash(normalizedSource, fileHash)
				.orElseThrow(() -> new IllegalStateException(
						"settlement_import claim conflicted but no existing row was found"));
		return new SettlementImportOutcome(winner, true);
	}

}
