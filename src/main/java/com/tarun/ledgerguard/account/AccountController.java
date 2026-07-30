package com.tarun.ledgerguard.account;

import com.tarun.ledgerguard.account.dto.AccountBalanceResponse;
import com.tarun.ledgerguard.account.dto.AccountResponse;
import com.tarun.ledgerguard.account.dto.CreateAccountRequest;
import com.tarun.ledgerguard.account.dto.DepositRequest;
import com.tarun.ledgerguard.account.dto.DepositResponse;
import com.tarun.ledgerguard.account.dto.TransactionHistoryItem;
import com.tarun.ledgerguard.common.PagedResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Account creation (Task 3), deposits (Task 4), and account balance/
 * transaction-history reads (Task 6) from docs/API_SPEC.md. Transfers live
 * in {@code transfer.TransferController}. Plain
 * {@code GET /api/v1/accounts/{id}} remains deferred — docs/TASKS.md's
 * Task 6 line scopes this task to balance and transaction history only.
 *
 * Error handling here is intentionally minimal: bean-validation failures
 * (missing/malformed request fields, malformed path/query values, and
 * out-of-range pagination parameters) are handled by Spring's default
 * exception resolution (400); the domain-specific cases (unsupported/
 * mismatched currency → 422, account not found → 404) are handled by the
 * shared {@code common.AccountAndTransferExceptionHandler} (also used by
 * transfers). The full cross-cutting error-response framework is Task 7's
 * responsibility, not reproduced here.
 */
@Validated
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

	private final AccountService accountService;
	private final DepositService depositService;
	private final AccountQueryService accountQueryService;

	public AccountController(AccountService accountService, DepositService depositService,
			AccountQueryService accountQueryService) {
		this.accountService = accountService;
		this.depositService = depositService;
		this.accountQueryService = accountQueryService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
		return accountService.createCustomerWalletAccount(request);
	}

	@PostMapping("/{id}/deposits")
	@ResponseStatus(HttpStatus.CREATED)
	public DepositResponse deposit(@PathVariable("id") UUID accountId, @Valid @RequestBody DepositRequest request) {
		return depositService.deposit(accountId, request);
	}

	@GetMapping("/{id}/balance")
	public AccountBalanceResponse getBalance(@PathVariable("id") UUID accountId) {
		return accountQueryService.getBalance(accountId);
	}

	@GetMapping("/{id}/transactions")
	public PagedResponse<TransactionHistoryItem> getTransactionHistory(
			@PathVariable("id") UUID accountId,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
		return accountQueryService.getTransactionHistory(accountId, page, size);
	}

}
