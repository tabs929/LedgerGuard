package com.tarun.ledgerguard.account;

import com.tarun.ledgerguard.account.dto.AccountResponse;
import com.tarun.ledgerguard.account.dto.CreateAccountRequest;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

/**
 * Public account creation is limited to customer wallets. The taxonomy
 * (CUSTOMER + LIABILITY + CUSTOMER_WALLET) and the zero opening balance are
 * fixed by this service, not accepted from the caller — CreateAccountRequest
 * has no fields for them, so there is nothing here for a client value to
 * override.
 */
@Service
public class AccountService {

	private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD");
	private static final BigDecimal OPENING_BALANCE = BigDecimal.ZERO.setScale(4);

	private final AccountRepository accountRepository;
	private final EntityManager entityManager;

	public AccountService(AccountRepository accountRepository, EntityManager entityManager) {
		this.accountRepository = accountRepository;
		this.entityManager = entityManager;
	}

	@Transactional
	public AccountResponse createCustomerWalletAccount(CreateAccountRequest request) {
		String normalizedCurrency = request.currency().toUpperCase(Locale.ROOT);
		if (!SUPPORTED_CURRENCIES.contains(normalizedCurrency)) {
			throw new UnsupportedCurrencyException(normalizedCurrency);
		}

		Account account = new Account(
				AccountCategory.CUSTOMER,
				AccountClass.LIABILITY,
				AccountPurpose.CUSTOMER_WALLET,
				request.ownerName(),
				normalizedCurrency,
				OPENING_BALANCE);

		Account saved = accountRepository.save(account);

		// The insert relies on the database's `created_at DEFAULT now()`.
		// Hibernate's insert-time value generation does not reliably
		// populate createdAt on the in-memory entity for this column/type,
		// so it is explicitly reloaded from the database after the insert
		// is flushed, guaranteeing the response reflects the true DB value.
		accountRepository.flush();
		entityManager.refresh(saved);

		return new AccountResponse(
				saved.getId(),
				saved.getOwnerName(),
				saved.getCurrency(),
				saved.getBalance(),
				saved.getCreatedAt());
	}

}
