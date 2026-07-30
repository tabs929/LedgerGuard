package com.tarun.ledgerguard.common;

import com.tarun.ledgerguard.account.AccountNotFoundException;
import com.tarun.ledgerguard.account.CurrencyMismatchException;
import com.tarun.ledgerguard.account.InsufficientFundsException;
import com.tarun.ledgerguard.account.SameAccountTransferException;
import com.tarun.ledgerguard.account.UnsupportedCurrencyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Minimal, shared error mapping for the account-creation, deposit, and
 * transfer endpoints (Tasks 3–5) — not the complete cross-cutting
 * error-response framework, which is Task 7's responsibility. This exists
 * only so the same handful of domain exceptions aren't mapped twice, once
 * per controller, now that both {@code AccountController} and
 * {@code TransferController} can throw them. Bean-validation failures and
 * unknown-JSON-property rejections are still left to Spring's own default
 * exception resolution (400), unchanged.
 */
@RestControllerAdvice
public class AccountAndTransferExceptionHandler {

	@ExceptionHandler({ UnsupportedCurrencyException.class, CurrencyMismatchException.class,
			InsufficientFundsException.class, SameAccountTransferException.class })
	public ResponseEntity<ErrorBody> handleUnprocessable(RuntimeException ex) {
		return ResponseEntity.unprocessableEntity().body(new ErrorBody(ex.getMessage()));
	}

	@ExceptionHandler(AccountNotFoundException.class)
	public ResponseEntity<ErrorBody> handleAccountNotFound(AccountNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorBody(ex.getMessage()));
	}

	private record ErrorBody(String message) {
	}

}
