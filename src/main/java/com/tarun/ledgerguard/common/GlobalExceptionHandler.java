package com.tarun.ledgerguard.common;

import com.tarun.ledgerguard.account.AccountNotFoundException;
import com.tarun.ledgerguard.account.CurrencyMismatchException;
import com.tarun.ledgerguard.account.InsufficientFundsException;
import com.tarun.ledgerguard.account.SameAccountTransferException;
import com.tarun.ledgerguard.account.UnsupportedCurrencyException;
import com.tarun.ledgerguard.idempotency.IdempotencyConflictException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * The single centralized error-translation point for every public
 * controller in the application (Task 7). Every branch below produces the
 * exact same {@link ApiError} envelope documented in docs/API_SPEC.md — no
 * controller has its own local {@code @ExceptionHandler} anymore (this
 * replaces the account/transfer-only {@code AccountAndTransferExceptionHandler}
 * from Tasks 5–6, preserving every status code it already produced).
 *
 * <p>Only exception types this application's controllers actually produce
 * are handled here (verified empirically, not assumed) — see
 * docs/ARCHITECTURE.md's "Error Handling" section for the specific
 * reasoning behind each one, and why some Spring/Jakarta validation
 * exception types that exist in the framework are deliberately not
 * handled here (they're never thrown by this application's controller
 * signatures).
 *
 * <p>This class never runs inside a write service's {@code @Transactional}
 * method — it only ever receives an exception after that method has
 * already thrown and Spring has already rolled back the surrounding
 * transaction. Translating the exception into an HTTP response here is a
 * purely post-hoc, read-only concern; it cannot un-roll-back anything, and
 * nothing here ever opens a new transaction or writes financial state.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	// -- domain exceptions (Tasks 3-6), unchanged status codes ----------

	@ExceptionHandler(AccountNotFoundException.class)
	public ResponseEntity<ApiError> handleAccountNotFound(AccountNotFoundException ex, HttpServletRequest request) {
		return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
	}

	@ExceptionHandler({ UnsupportedCurrencyException.class, CurrencyMismatchException.class,
			InsufficientFundsException.class, SameAccountTransferException.class })
	public ResponseEntity<ApiError> handleUnprocessable(RuntimeException ex, HttpServletRequest request) {
		return build(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage(), request);
	}

	// -- idempotency (Task 10) --------------------------------------------

	// Idempotency-Key reused for a command that does not canonically match
	// the one it was first claimed for (including reuse across deposit vs.
	// transfer). ex.getMessage() is safe to return here -- it never
	// contains anything beyond the key itself.
	@ExceptionHandler(IdempotencyConflictException.class)
	public ResponseEntity<ApiError> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request) {
		return build(HttpStatus.CONFLICT, ex.getMessage(), request);
	}

	// -- request-shape validation (400) ----------------------------------

	// The required Idempotency-Key header (deposits, transfers) is absent
	// entirely. A blank/too-long/invalid-character value is instead caught
	// by @Pattern below (ConstraintViolationException).
	@ExceptionHandler(MissingRequestHeaderException.class)
	public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, ex.getHeaderName() + ": header is required.", request);
	}

	// @Valid @RequestBody failures: blank/missing fields, non-positive or
	// out-of-precision amounts, malformed currency pattern, etc. Multiple
	// field errors are sorted by field name for a deterministic message.
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiError> handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.sorted(Comparator.comparing(FieldError::getField))
				.map(error -> error.getField() + ": " + error.getDefaultMessage())
				.collect(Collectors.joining("; "));
		return build(HttpStatus.BAD_REQUEST, message, request);
	}

	// @Validated method-parameter constraints (the transaction-history
	// endpoint's @Min/@Max on page/size) are enforced via Bean Validation's
	// AOP method interceptor, which throws this JSR-380 exception directly
	// -- distinct from the web-layer exception types above.
	@ExceptionHandler(ConstraintViolationException.class)
	public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
		String message = ex.getConstraintViolations().stream()
				.sorted(Comparator.comparing(v -> v.getPropertyPath().toString()))
				.map(GlobalExceptionHandler::describeViolation)
				.collect(Collectors.joining("; "));
		return build(HttpStatus.BAD_REQUEST, message, request);
	}

	// Malformed JSON syntax, an unrecognized (protected/unknown) JSON
	// property, or a value that fails to coerce into its target type (a
	// non-numeric "amount", a malformed UUID inside the request body,
	// etc.). Deliberately generic -- the underlying cause can contain
	// Jackson/Hibernate class names and is never echoed back.
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiError> handleMalformedBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, "Malformed request body.", request);
	}

	// A path variable (e.g. {id}) that fails to convert to its declared
	// type -- a non-UUID account id. The parameter name is public API
	// surface; the rejected value itself is not echoed back.
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
		return build(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + ex.getName() + "'.", request);
	}

	// -- fallback (500) ---------------------------------------------------

	// Anything not explicitly handled above -- including persistence
	// failures (e.g. a database-level constraint violation) that were not
	// caught earlier by application-level validation. The original
	// exception is logged once, server-side, for diagnosis; the client
	// only ever sees a generic, safe message.
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Unhandled exception processing {} {}", request.getMethod(), request.getRequestURI(), ex);
		return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.", request);
	}

	private static String describeViolation(ConstraintViolation<?> violation) {
		String path = violation.getPropertyPath().toString();
		int lastDot = path.lastIndexOf('.');
		String parameterName = lastDot >= 0 ? path.substring(lastDot + 1) : path;
		return parameterName + ": " + violation.getMessage();
	}

	private ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request) {
		ApiError body = new ApiError(
				Instant.now().toString(),
				status.value(),
				status.getReasonPhrase(),
				message,
				request.getRequestURI());
		return ResponseEntity.status(status).body(body);
	}

}
