package com.tarun.ledgerguard.reconciliation;

import com.tarun.ledgerguard.ledger.TransactionType;

import java.util.List;
import java.util.UUID;

/**
 * A bulk-loaded, read-only view of one {@code ledger_transaction} and its
 * {@code ledger_entry} rows, assembled by {@code LedgerDataLoader} for
 * matching purposes only. Never persisted, never mutated, never written
 * back — Task 15 only ever reads this data.
 */
record LedgerTransactionView(UUID transactionId, TransactionType transactionType, List<LedgerEntryView> entries) {
}
