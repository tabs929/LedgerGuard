package com.tarun.ledgerguard.account;

import com.tarun.ledgerguard.account.dto.AccountResponse;
import com.tarun.ledgerguard.account.dto.CreateAccountRequest;
import com.tarun.ledgerguard.account.dto.DepositRequest;
import com.tarun.ledgerguard.account.dto.DepositResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Account creation (Task 3) and deposits (Task 4) from docs/API_SPEC.md.
 * GET /api/v1/accounts/{id}, balance/history and transfers are part of the
 * same documented contract but remain deferred — see docs/TASKS.md.
 *
 * Error handling here is intentionally minimal: bean-validation failures
 * (missing/malformed fields) and unknown-JSON-property rejections are
 * handled by Spring's default exception resolution (400), and the
 * domain-specific cases (unsupported/mismatched currency → 422, account not
 * found → 404) are handled locally below. The full cross-cutting
 * error-response framework is Task 7's responsibility, not reproduced here.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

	private final AccountService accountService;
	private final DepositService depositService;

	public AccountController(AccountService accountService, DepositService depositService) {
		this.accountService = accountService;
		this.depositService = depositService;
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

	@ExceptionHandler({ UnsupportedCurrencyException.class, CurrencyMismatchException.class })
	public ResponseEntity<ErrorBody> handleCurrencyErrors(RuntimeException ex) {
		return ResponseEntity.unprocessableEntity().body(new ErrorBody(ex.getMessage()));
	}

	@ExceptionHandler(AccountNotFoundException.class)
	public ResponseEntity<ErrorBody> handleAccountNotFound(AccountNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody(ex.getMessage()));
	}

	private record ErrorBody(String message) {
	}

}
