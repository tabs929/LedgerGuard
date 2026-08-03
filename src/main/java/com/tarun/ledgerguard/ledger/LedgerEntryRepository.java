package com.tarun.ledgerguard.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID> {

	/**
	 * One account's own ledger-entry history, newest first
	 * (created_at DESC, id DESC — the approved ordering contract in
	 * docs/API_SPEC.md), one page at a time. Spring Data issues exactly two
	 * queries per call — the page content (LIMIT/OFFSET) and the total
	 * count — never loads the account's full history into memory, and
	 * never issues a query per row. The existing
	 * idx_ledger_entry_account_id(account_id, created_at) index (from
	 * V1__init_account_ledger_schema.sql) supports the account_id filter
	 * and created_at ordering directly.
	 */
	Page<LedgerEntry> findByAccountIdOrderByCreatedAtDescIdDesc(UUID accountId, Pageable pageable);

	/**
	 * Every entry for a set of transactions in one query — Task 15's
	 * {@code reconciliation.LedgerDataLoader} uses this to bulk-load every
	 * entry for every reported transaction in one settlement import,
	 * rather than one query per transaction.
	 */
	List<LedgerEntry> findByTransactionIdIn(Collection<UUID> transactionIds);

}
