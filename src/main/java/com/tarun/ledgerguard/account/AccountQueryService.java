package com.tarun.ledgerguard.account;

import com.tarun.ledgerguard.account.dto.AccountBalanceResponse;
import com.tarun.ledgerguard.account.dto.TransactionHistoryItem;
import com.tarun.ledgerguard.common.PagedResponse;
import com.tarun.ledgerguard.ledger.LedgerEntry;
import com.tarun.ledgerguard.ledger.LedgerEntryRepository;
import com.tarun.ledgerguard.security.AuthenticatedPrincipal;
import com.tarun.ledgerguard.security.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read-only account queries: current materialized balance (Task 6) and
 * ledger-entry history (Task 6). Both methods are pure reads —
 * {@code @Transactional(readOnly = true)}, no row locking (there is
 * nothing to protect against a concurrent writer here: PostgreSQL's
 * committed-read isolation, combined with deposits/transfers already
 * writing balance and ledger rows atomically in Tasks 4–5, is what makes
 * a plain read see a consistent, already-correct state), and neither
 * method ever constructs a {@code LedgerTransaction}/{@code LedgerEntry}
 * or mutates an {@code Account}.
 */
@Service
public class AccountQueryService {

	private final AccountRepository accountRepository;
	private final LedgerEntryRepository ledgerEntryRepository;

	public AccountQueryService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository) {
		this.accountRepository = accountRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
	}

	@Transactional(readOnly = true)
	public AccountBalanceResponse getBalance(UUID accountId, AuthenticatedPrincipal principal) {
		Account account = resolveCustomerWallet(accountId, principal);
		return new AccountBalanceResponse(account.getId(), account.getBalance(), account.getCurrency());
	}

	@Transactional(readOnly = true)
	public PagedResponse<TransactionHistoryItem> getTransactionHistory(UUID accountId, AuthenticatedPrincipal principal,
			int page, int size) {
		// Existence/taxonomy/ownership check only -- the account itself
		// carries no history data; entries are fetched separately below.
		resolveCustomerWallet(accountId, principal);

		Page<LedgerEntry> entries = ledgerEntryRepository.findByAccountIdOrderByCreatedAtDescIdDesc(
				accountId, PageRequest.of(page, size));

		List<TransactionHistoryItem> content = entries.getContent().stream()
				.map(entry -> new TransactionHistoryItem(
						entry.getTransactionId(),
						entry.getEntryType(),
						entry.getAmount(),
						entry.getCurrency(),
						entry.getCreatedAt()))
				.toList();

		return new PagedResponse<>(content, page, size, entries.getTotalElements(), entries.getTotalPages());
	}

	// A SYSTEM account id (or any non-customer-wallet row) is treated
	// identically to a nonexistent id, per docs/API_SPEC.md -- same rule
	// deposits and transfers already apply to their own account inputs.
	// Task 17: a CUSTOMER principal reading an account they do not own is
	// treated identically as well (404, not 403) -- this account-specific
	// ownership violation follows the project's existing SYSTEM-account
	// precedent of never disclosing that a restricted id is valid.
	// OPERATIONS may read any customer wallet.
	private Account resolveCustomerWallet(UUID accountId, AuthenticatedPrincipal principal) {
		Account account = accountRepository.findById(accountId)
				.orElseThrow(() -> new AccountNotFoundException(accountId));
		if (account.getAccountCategory() != AccountCategory.CUSTOMER
				|| account.getAccountClass() != AccountClass.LIABILITY
				|| account.getAccountPurpose() != AccountPurpose.CUSTOMER_WALLET) {
			throw new AccountNotFoundException(accountId);
		}
		if (principal.role() == Role.CUSTOMER && !account.getCustomerSubject().equals(principal.subject())) {
			throw new AccountNotFoundException(accountId);
		}
		return account;
	}

}
