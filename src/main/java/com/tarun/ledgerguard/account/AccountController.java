package com.tarun.ledgerguard.account;

import com.tarun.ledgerguard.account.dto.AccountResponse;
import com.tarun.ledgerguard.account.dto.CreateAccountRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Only the account-creation endpoint from docs/API_SPEC.md is implemented
 * here (Task 3). GET /api/v1/accounts/{id} is part of the same documented
 * contract but is deferred — see docs/TASKS.md for the scope note.
 *
 * Error handling here is intentionally minimal: bean-validation failures
 * (missing/malformed fields) and unknown-JSON-property rejections are
 * handled by Spring's default exception resolution (400), and the one
 * domain-specific case (unsupported currency, 422) is handled locally
 * below. The full cross-cutting error-response framework is Task 7's
 * responsibility, not reproduced here.
 */
@RestController
@RequestMapping("/api/v1/accounts")
public class AccountController {

	private final AccountService accountService;

	public AccountController(AccountService accountService) {
		this.accountService = accountService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
		return accountService.createCustomerWalletAccount(request);
	}

	@ExceptionHandler(UnsupportedCurrencyException.class)
	public ResponseEntity<ErrorBody> handleUnsupportedCurrency(UnsupportedCurrencyException ex) {
		return ResponseEntity.unprocessableEntity().body(new ErrorBody(ex.getMessage()));
	}

	private record ErrorBody(String message) {
	}

}
