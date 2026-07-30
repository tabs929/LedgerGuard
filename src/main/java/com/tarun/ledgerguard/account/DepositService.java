package com.tarun.ledgerguard.account;

import com.tarun.ledgerguard.account.dto.DepositRequest;
import com.tarun.ledgerguard.account.dto.DepositResponse;
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

	public DepositService(AccountRepository accountRepository,
			LedgerTransactionRepository ledgerTransactionRepository,
			LedgerEntryRepository ledgerEntryRepository,
			EntityManager entityManager) {
		this.accountRepository = accountRepository;
		this.ledgerTransactionRepository = ledgerTransactionRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.entityManager = entityManager;
	}

	@Transactional
	public DepositResponse deposit(UUID destinationAccountId, DepositRequest request) {
		String normalizedCurrency = request.currency().toUpperCase(Locale.ROOT);
		if (!SUPPORTED_CURRENCY.equals(normalizedCurrency)) {
			throw new UnsupportedCurrencyException(normalizedCurrency);
		}
		// @Digits(fraction = 4) on DepositRequest already guarantees no more
		// than 4 fractional digits, so this only pads scale -- never rounds.
		BigDecimal amount = request.amount().setScale(4, RoundingMode.UNNECESSARY);

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

		return new DepositResponse(
				transaction.getId(),
				destinationAccount.getId(),
				amount,
				normalizedCurrency,
				destinationAccount.getBalance(),
				transaction.getCreatedAt());
	}

}
