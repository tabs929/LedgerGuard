package com.tarun.ledgerguard.inbox;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused unit coverage (Task 13) for the identical-vs-conflicting
 * duplicate comparison {@link ProcessedEventRecord#matches} implements —
 * no database involved.
 */
class ProcessedEventRecordTest {

	private static final UUID EVENT_ID = UUID.randomUUID();
	private static final UUID AGGREGATE_ID = UUID.randomUUID();
	private static final String HASH_A = "a".repeat(64);
	private static final String HASH_B = "b".repeat(64);

	private final ProcessedEventRecord existing = new ProcessedEventRecord(
			EVENT_ID, AGGREGATE_ID, "DEPOSIT_COMPLETED", 1, HASH_A, "ledger.transaction-events.v1", 0, 5L,
			Instant.now());

	@Test
	void identicalRedeliveryMatches() {
		ValidatedLedgerEvent redelivered = new ValidatedLedgerEvent(EVENT_ID, AGGREGATE_ID, "DEPOSIT_COMPLETED", 1);
		assertThat(existing.matches(redelivered, HASH_A)).isTrue();
	}

	@Test
	void identicalRedeliveryAtADifferentSourcePositionStillMatches() {
		// matches() never looks at source topic/partition/offset -- a
		// legitimate redelivery may land at a different Kafka position.
		ProcessedEventRecord sameEventDifferentOffset = new ProcessedEventRecord(
				EVENT_ID, AGGREGATE_ID, "DEPOSIT_COMPLETED", 1, HASH_A, "ledger.transaction-events.v1", 2, 999L,
				Instant.now());
		ValidatedLedgerEvent redelivered = new ValidatedLedgerEvent(EVENT_ID, AGGREGATE_ID, "DEPOSIT_COMPLETED", 1);
		assertThat(sameEventDifferentOffset.matches(redelivered, HASH_A)).isTrue();
	}

	@Test
	void differentAggregateIdConflicts() {
		ValidatedLedgerEvent conflicting = new ValidatedLedgerEvent(EVENT_ID, UUID.randomUUID(), "DEPOSIT_COMPLETED", 1);
		assertThat(existing.matches(conflicting, HASH_A)).isFalse();
	}

	@Test
	void differentEventTypeConflicts() {
		ValidatedLedgerEvent conflicting = new ValidatedLedgerEvent(EVENT_ID, AGGREGATE_ID, "TRANSFER_COMPLETED", 1);
		assertThat(existing.matches(conflicting, HASH_A)).isFalse();
	}

	@Test
	void differentSchemaVersionConflicts() {
		ValidatedLedgerEvent conflicting = new ValidatedLedgerEvent(EVENT_ID, AGGREGATE_ID, "DEPOSIT_COMPLETED", 2);
		assertThat(existing.matches(conflicting, HASH_A)).isFalse();
	}

	@Test
	void differentPayloadHashConflicts() {
		ValidatedLedgerEvent sameMetadata = new ValidatedLedgerEvent(EVENT_ID, AGGREGATE_ID, "DEPOSIT_COMPLETED", 1);
		assertThat(existing.matches(sameMetadata, HASH_B)).isFalse();
	}

}
