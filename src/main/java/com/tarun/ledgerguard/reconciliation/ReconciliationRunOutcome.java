package com.tarun.ledgerguard.reconciliation;

/**
 * The result of one call to {@code ReconciliationProcessor.reconcile}: the
 * committed {@code reconciliation_run} row, and whether it is a fresh run
 * (201) or a replay of an already-committed run for the same
 * (settlement import, algorithm version) (200).
 */
record ReconciliationRunOutcome(StoredReconciliationRun run, boolean replayed) {
}
