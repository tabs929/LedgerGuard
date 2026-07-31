package com.tarun.ledgerguard.transfer;

import com.tarun.ledgerguard.account.Account;
import com.tarun.ledgerguard.account.AccountCategory;
import com.tarun.ledgerguard.account.AccountClass;
import com.tarun.ledgerguard.account.AccountNotFoundException;
import com.tarun.ledgerguard.account.AccountPurpose;
import com.tarun.ledgerguard.account.AccountRepository;
import com.tarun.ledgerguard.account.CurrencyMismatchException;
import com.tarun.ledgerguard.account.InsufficientFundsException;
import com.tarun.ledgerguard.account.SameAccountTransferException;
import com.tarun.ledgerguard.account.UnsupportedCurrencyException;
import com.tarun.ledgerguard.idempotency.IdempotencyCommand;
import com.tarun.ledgerguard.idempotency.IdempotencyService;
import com.tarun.ledgerguard.ledger.LedgerEntry;
import com.tarun.ledgerguard.ledger.LedgerEntryRepository;
import com.tarun.ledgerguard.ledger.LedgerEntryType;
import com.tarun.ledgerguard.ledger.LedgerTransaction;
import com.tarun.ledgerguard.ledger.LedgerTransactionRepository;
import com.tarun.ledgerguard.ledger.TransactionStatus;
import com.tarun.ledgerguard.ledger.TransactionType;
import com.tarun.ledgerguard.transfer.dto.TransferRequest;
import com.tarun.ledgerguard.transfer.dto.TransferResponse;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * A transfer is a single balanced double-entry ledger transaction between
 * two customer wallets: DEBIT the source (decreasing its liability
 * balance), CREDIT the destination (increasing its liability balance),
 * both for the same amount and currency. Both materialized balances are
 * updated in the same database transaction as the two ledger entries —
 * never independently of them. The EXTERNAL_FUNDING account is never
 * involved in a customer-to-customer transfer.
 *
 * <p>Reuses the {@code ledger} entities/repositories and the
 * {@code account} exceptions built for deposits (Task 4) — see
 * {@link AccountRepository#findByIdsForUpdate} for the deterministic
 * lock-ordering primitive this service relies on, which differs from
 * deposit's locking query only in that both account ids here are already
 * known (an explicit source and destination), rather than one being
 * resolved internally by taxonomy.
 */
@Service
public class TransferService {

	private static final String SUPPORTED_CURRENCY = "USD";

	private final AccountRepository accountRepository;
	private final LedgerTransactionRepository ledgerTransactionRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final EntityManager entityManager;
	private final IdempotencyService idempotencyService;

	public TransferService(AccountRepository accountRepository,
			LedgerTransactionRepository ledgerTransactionRepository,
			LedgerEntryRepository ledgerEntryRepository,
			EntityManager entityManager,
			IdempotencyService idempotencyService) {
		this.accountRepository = accountRepository;
		this.ledgerTransactionRepository = ledgerTransactionRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.entityManager = entityManager;
		this.idempotencyService = idempotencyService;
	}

	@Transactional
	public TransferResponse transfer(TransferRequest request, String idempotencyKey) {
		UUID sourceAccountId = request.sourceAccountId();
		UUID destinationAccountId = request.destinationAccountId();
		// Normalizing here (before the idempotency claim below) is what lets
		// "100", "100.0" and "100.00" compare as the same command; the
		// same-account/currency-support checks below stay in doTransfer so
		// their relative throw order is unchanged from before Task 10.
		String normalizedCurrency = request.currency().toUpperCase(Locale.ROOT);
		// @Digits(fraction = 4) on TransferRequest already guarantees no more
		// than 4 fractional digits, so this only pads scale -- never rounds.
		BigDecimal amount = request.amount().setScale(4, RoundingMode.UNNECESSARY);

		IdempotencyCommand command =
				IdempotencyCommand.forTransfer(sourceAccountId, destinationAccountId, amount, normalizedCurrency);
		return idempotencyService.execute(idempotencyKey, command, TransferResponse.class, TransferResponse::transactionId,
				() -> doTransfer(sourceAccountId, destinationAccountId, normalizedCurrency, amount));
	}

	private TransferResponse doTransfer(UUID sourceAccountId, UUID destinationAccountId, String normalizedCurrency,
			BigDecimal amount) {
		if (sourceAccountId.equals(destinationAccountId)) {
			throw new SameAccountTransferException(sourceAccountId);
		}
		if (!SUPPORTED_CURRENCY.equals(normalizedCurrency)) {
			throw new UnsupportedCurrencyException(normalizedCurrency);
		}

		// Deterministic lock ordering: both rows are locked by the query's
		// own `ORDER BY a.id`, regardless of the (arbitrary) order the two
		// ids are passed in here -- this is what prevents a deadlock between
		// concurrent opposite-direction transfers (A->B and B->A).
		List<Account> lockedAccounts =
				accountRepository.findByIdsForUpdate(List.of(sourceAccountId, destinationAccountId));

		Account source = findAccountOrThrow(lockedAccounts, sourceAccountId);
		Account destination = findAccountOrThrow(lockedAccounts, destinationAccountId);

		requireCustomerWallet(source);
		requireCustomerWallet(destination);

		if (!source.getCurrency().equals(normalizedCurrency)) {
			throw new CurrencyMismatchException(sourceAccountId, source.getCurrency(), normalizedCurrency);
		}
		if (!destination.getCurrency().equals(normalizedCurrency)) {
			throw new CurrencyMismatchException(destinationAccountId, destination.getCurrency(), normalizedCurrency);
		}

		if (source.getBalance().compareTo(amount) < 0) {
			throw new InsufficientFundsException(sourceAccountId, source.getBalance(), amount);
		}

		LedgerTransaction transaction =
				ledgerTransactionRepository.save(new LedgerTransaction(TransactionType.TRANSFER, TransactionStatus.COMPLETED));

		ledgerEntryRepository.save(
				new LedgerEntry(transaction.getId(), source.getId(), LedgerEntryType.DEBIT, amount, normalizedCurrency));
		ledgerEntryRepository.save(
				new LedgerEntry(transaction.getId(), destination.getId(), LedgerEntryType.CREDIT, amount, normalizedCurrency));

		source.decreaseBalance(amount);
		destination.increaseBalance(amount);

		// Flush now so any constraint violation surfaces here, inside the
		// transaction, rather than silently at a later, unrelated flush point.
		ledgerTransactionRepository.flush();
		entityManager.refresh(transaction);

		return new TransferResponse(
				transaction.getId(),
				source.getId(),
				destination.getId(),
				amount,
				normalizedCurrency,
				transaction.getCreatedAt());
	}

	private Account findAccountOrThrow(List<Account> accounts, UUID accountId) {
		return accounts.stream()
				.filter(account -> account.getId().equals(accountId))
				.findFirst()
				.orElseThrow(() -> new AccountNotFoundException(accountId));
	}

	// A SYSTEM account id (or any non-customer-wallet row) is treated
	// identically to a nonexistent id, per docs/API_SPEC.md.
	private void requireCustomerWallet(Account account) {
		if (account.getAccountCategory() != AccountCategory.CUSTOMER
				|| account.getAccountClass() != AccountClass.LIABILITY
				|| account.getAccountPurpose() != AccountPurpose.CUSTOMER_WALLET) {
			throw new AccountNotFoundException(account.getId());
		}
	}

}
