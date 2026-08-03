package com.tarun.ledgerguard.reconciliation;

import com.tarun.ledgerguard.account.Account;
import com.tarun.ledgerguard.account.AccountRepository;
import com.tarun.ledgerguard.ledger.LedgerEntry;
import com.tarun.ledgerguard.ledger.LedgerEntryRepository;
import com.tarun.ledgerguard.ledger.LedgerTransaction;
import com.tarun.ledgerguard.ledger.LedgerTransactionRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Bulk-loads every {@link LedgerTransactionView} a batch of reported
 * transaction ids could possibly need, in exactly three queries
 * regardless of how many ids are requested — never one query per
 * transaction (see docs/ARCHITECTURE.md's "Settlement Reconciliation"
 * section, "Performance" subsection). Read-only: this class never writes
 * to {@code ledger_transaction}, {@code ledger_entry}, or {@code account}.
 */
@Component
class LedgerDataLoader {

	private final LedgerTransactionRepository transactionRepository;
	private final LedgerEntryRepository entryRepository;
	private final AccountRepository accountRepository;

	LedgerDataLoader(LedgerTransactionRepository transactionRepository, LedgerEntryRepository entryRepository,
			AccountRepository accountRepository) {
		this.transactionRepository = transactionRepository;
		this.entryRepository = entryRepository;
		this.accountRepository = accountRepository;
	}

	/**
	 * Returns a map keyed by transaction id, containing only the entries
	 * found in {@code ledger_transaction} — a reported id absent from the
	 * result map has no {@code ledger_transaction} row at all (the caller
	 * treats a missing key as {@link ReconciliationOutcome#INTERNAL_TRANSACTION_NOT_FOUND}).
	 */
	Map<UUID, LedgerTransactionView> loadByTransactionIds(Set<UUID> transactionIds) {
		if (transactionIds.isEmpty()) {
			return Map.of();
		}

		List<LedgerTransaction> transactions = transactionRepository.findAllById(transactionIds);
		List<LedgerEntry> entries = entryRepository.findByTransactionIdIn(transactionIds);

		Set<UUID> accountIds = entries.stream().map(LedgerEntry::getAccountId).collect(Collectors.toSet());
		Map<UUID, Account> accountsById = accountRepository.findAllById(accountIds).stream()
				.collect(Collectors.toMap(Account::getId, account -> account));

		Map<UUID, List<LedgerEntry>> entriesByTransactionId = entries.stream()
				.collect(Collectors.groupingBy(LedgerEntry::getTransactionId));

		return transactions.stream().collect(Collectors.toMap(LedgerTransaction::getId, transaction -> {
			List<LedgerEntryView> entryViews = entriesByTransactionId
					.getOrDefault(transaction.getId(), List.of()).stream()
					.map(entry -> toEntryView(entry, accountsById.get(entry.getAccountId())))
					.toList();
			return new LedgerTransactionView(transaction.getId(), transaction.getTransactionType(), entryViews);
		}));
	}

	private LedgerEntryView toEntryView(LedgerEntry entry, Account account) {
		// account is always present: every ledger_entry.account_id is a
		// NOT NULL foreign key to account(id) (V1), and accounts are
		// never deleted -- a null here would itself be a data-integrity
		// impossibility under the schema's own guarantees, not a
		// reconciliation case to handle gracefully.
		return new LedgerEntryView(entry.getEntryType(), entry.getAmount(), entry.getCurrency(),
				account.getAccountCategory(), account.getAccountClass(), account.getAccountPurpose());
	}

}
