package com.tarun.ledgerguard.account;

import com.tarun.ledgerguard.account.dto.DepositRequest;
import com.tarun.ledgerguard.account.dto.DepositResponse;
import com.tarun.ledgerguard.idempotency.IdempotencyCommand;
import com.tarun.ledgerguard.idempotency.IdempotencyService;
import com.tarun.ledgerguard.outbox.OutboxEventFactory;
import com.tarun.ledgerguard.security.AuthenticatedPrincipal;
import com.tarun.ledgerguard.security.AuthorizationSupport;
import com.tarun.ledgerguard.security.Role;
import com.tarun.ledgerguard.ledger.LedgerEntry;
import com.tarun.ledgerguard.ledger.LedgerEntryRepository;
import com.tarun.ledgerguard.ledger.LedgerEntryType;
import com.tarun.ledgerguard.ledger.LedgerTransaction;
import com.tarun.ledgerguard.ledger.LedgerTransactionRepository;
import com.tarun.ledgerguard.ledger.TransactionStatus;
import com.tarun.ledgerguard.ledger.TransactionType;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A deposit is a single balanced double-entry ledger transaction: DEBIT the
 * SYSTEM/ASSET/EXTERNAL_FUNDING account, CREDIT the destination
 * CUSTOMER/LIABILITY/CUSTOMER_WALLET account, both for the same amount and
 * currency. Both materialized balances are updated in the same database
 * transaction as the two ledger entries — never independently of them.
 *
 * <p>The funding account is never supplied by the caller; it is resolved
 * internally from its protected taxonomy (see
 * {@link AccountRepository#findByIdAndFundingAccountForUpdate}), which also
 * locks both participating account rows in ascending id order in one query
 * — the deterministic lock ordering documented in docs/ARCHITECTURE.md that
 * prevents a lost update on the shared funding row under concurrent
 * deposits to different customers.
 */
@Service
public class DepositService {

	private static final String SUPPORTED_CURRENCY = "USD";

	private final AccountRepository accountRepository;
	private final LedgerTransactionRepository ledgerTransactionRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final EntityManager entityManager;
	private final IdempotencyService idempotencyService;
	private final OutboxEventFactory outboxEventFactory;

	public DepositService(AccountRepository accountRepository,
			LedgerTransactionRepository ledgerTransactionRepository,
			LedgerEntryRepository ledgerEntryRepository,
			EntityManager entityManager,
			IdempotencyService idempotencyService,
			OutboxEventFactory outboxEventFactory) {
		this.accountRepository = accountRepository;
		this.ledgerTransactionRepository = ledgerTransactionRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.entityManager = entityManager;
		this.idempotencyService = idempotencyService;
		this.outboxEventFactory = outboxEventFactory;
	}

	@Transactional
	public DepositResponse deposit(UUID destinationAccountId, DepositRequest request, String idempotencyKey,
			AuthenticatedPrincipal principal) {
		// Task 17 ordering requirement: role, then ownership, then the
		// idempotency claim/replay, then (only for a genuinely new key) the
		// financial transaction itself -- so an idempotency replay can never
		// bypass authorization, and a cross-principal request never even
		// reaches the idempotency table for an account it doesn't own.
		AuthorizationSupport.requireRole(principal, Role.CUSTOMER);
		requireOwnedCustomerWallet(destinationAccountId, principal);

		String normalizedCurrency = request.currency().toUpperCase(Locale.ROOT);
		// @Digits(fraction = 4) on DepositRequest already guarantees no more
		// than 4 fractional digits, so this only pads scale -- never rounds.
		// Normalizing here (before the idempotency claim below) is what lets
		// "100", "100.0" and "100.00" compare as the same command.
		BigDecimal amount = request.amount().setScale(4, RoundingMode.UNNECESSARY);

		IdempotencyCommand command = IdempotencyCommand.forDeposit(destinationAccountId, amount, normalizedCurrency);
		return idempotencyService.execute(idempotencyKey, principal.subject(), command, DepositResponse.class,
				DepositResponse::transactionId, () -> doDeposit(destinationAccountId, normalizedCurrency, amount));
	}

	// Unlocked existence/taxonomy/ownership check, performed before the
	// idempotency claim/replay above. The actual mutation path (doDeposit)
	// re-resolves and locks the row independently -- this method never
	// substitutes for that locking. Critically, the account loaded here is
	// detached from the persistence context immediately afterward: leaving
	// it managed would make doDeposit's later PESSIMISTIC_WRITE query
	// return this same (by-then-stale) in-memory instance from Hibernate's
	// first-level cache instead of re-reading the just-locked row's true
	// balance, silently reintroducing the lost-update race Task 4's
	// deterministic locking was built to prevent -- confirmed empirically
	// by a failing concurrent-deposit test before this detach was added.
	private void requireOwnedCustomerWallet(UUID accountId, AuthenticatedPrincipal principal) {
		Account account = accountRepository.findById(accountId)
				.orElseThrow(() -> new AccountNotFoundException(accountId));
		if (account.getAccountCategory() != AccountCategory.CUSTOMER
				|| account.getAccountClass() != AccountClass.LIABILITY
				|| account.getAccountPurpose() != AccountPurpose.CUSTOMER_WALLET) {
			throw new AccountNotFoundException(accountId);
		}
		if (!account.getCustomerSubject().equals(principal.subject())) {
			throw new AccountNotFoundException(accountId);
		}
		entityManager.detach(account);
	}

	private DepositResponse doDeposit(UUID destinationAccountId, String normalizedCurrency, BigDecimal amount) {
		if (!SUPPORTED_CURRENCY.equals(normalizedCurrency)) {
			throw new UnsupportedCurrencyException(normalizedCurrency);
		}

		List<Account> lockedAccounts =
				accountRepository.findByIdAndFundingAccountForUpdate(destinationAccountId, normalizedCurrency);

		Account destinationAccount = lockedAccounts.stream()
				.filter(account -> account.getId().equals(destinationAccountId))
				.findFirst()
				.orElseThrow(() -> new AccountNotFoundException(destinationAccountId));

		// A SYSTEM account id (or any non-customer-wallet row) is treated
		// identically to a nonexistent id, per docs/API_SPEC.md -- this also
		// covers the case where destinationAccountId is the funding account's
		// own id.
		if (destinationAccount.getAccountCategory() != AccountCategory.CUSTOMER
				|| destinationAccount.getAccountClass() != AccountClass.LIABILITY
				|| destinationAccount.getAccountPurpose() != AccountPurpose.CUSTOMER_WALLET) {
			throw new AccountNotFoundException(destinationAccountId);
		}

		if (!destinationAccount.getCurrency().equals(normalizedCurrency)) {
			throw new CurrencyMismatchException(destinationAccountId, destinationAccount.getCurrency(), normalizedCurrency);
		}

		Account fundingAccount = lockedAccounts.stream()
				.filter(account -> account.getAccountCategory() == AccountCategory.SYSTEM
						&& account.getAccountPurpose() == AccountPurpose.EXTERNAL_FUNDING
						&& account.getCurrency().equals(normalizedCurrency))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException(
						"No EXTERNAL_FUNDING system account exists for currency " + normalizedCurrency));

		LedgerTransaction transaction =
				ledgerTransactionRepository.save(new LedgerTransaction(TransactionType.DEPOSIT, TransactionStatus.COMPLETED));

		ledgerEntryRepository.save(
				new LedgerEntry(transaction.getId(), fundingAccount.getId(), LedgerEntryType.DEBIT, amount, normalizedCurrency));
		ledgerEntryRepository.save(
				new LedgerEntry(transaction.getId(), destinationAccount.getId(), LedgerEntryType.CREDIT, amount, normalizedCurrency));

		fundingAccount.increaseBalance(amount);
		destinationAccount.increaseBalance(amount);

		// Flush now so any constraint violation (e.g. a corrupted schema
		// state) surfaces here, inside the transaction, rather than silently
		// at a later, unrelated flush point.
		ledgerTransactionRepository.flush();
		entityManager.refresh(transaction);

		outboxEventFactory.recordDepositCompleted(transaction.getId(), destinationAccount.getId(), amount,
				normalizedCurrency, transaction.getCreatedAt());

		return new DepositResponse(
				transaction.getId(),
				destinationAccount.getId(),
				amount,
				normalizedCurrency,
				destinationAccount.getBalance(),
				transaction.getCreatedAt());
	}

}
