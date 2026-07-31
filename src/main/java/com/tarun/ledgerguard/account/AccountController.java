package com.tarun.ledgerguard.account;

import com.tarun.ledgerguard.account.dto.AccountBalanceResponse;
import com.tarun.ledgerguard.account.dto.AccountResponse;
import com.tarun.ledgerguard.account.dto.CreateAccountRequest;
import com.tarun.ledgerguard.account.dto.DepositRequest;
import com.tarun.ledgerguard.account.dto.DepositResponse;
import com.tarun.ledgerguard.account.dto.TransactionHistoryItem;
import com.tarun.ledgerguard.common.ApiError;
import com.tarun.ledgerguard.common.PagedResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Account creation (Task 3), deposits (Task 4), and account balance/
 * transaction-history reads (Task 6) from docs/API_SPEC.md. Transfers live
 * in {@code transfer.TransferController}. Plain
 * {@code GET /api/v1/accounts/{id}} remains deferred — no task has been
 * assigned it so far.
 *
 * Error handling here is intentionally minimal: bean-validation failures
 * (missing/malformed request fields, malformed path/query values, and
 * out-of-range pagination parameters) are handled by Spring's default
 * exception resolution (400); the domain-specific cases (unsupported/
 * mismatched currency → 422, account not found → 404) are handled by the
 * shared {@code common.GlobalExceptionHandler} (also used by transfers).
 * See docs/ARCHITECTURE.md's "Error Handling" section for the complete
 * mapping (Task 7).
 */
@Validated
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Customer wallet creation, deposits, balance, and transaction history.")
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
	@Operation(summary = "Create a customer wallet account",
			description = "Creates a USD customer wallet account. The account always opens with a zero "
					+ "balance and the CUSTOMER/LIABILITY/CUSTOMER_WALLET taxonomy assigned by the server — "
					+ "these cannot be chosen by the client, and there is no request field for them. "
					+ "Unrecognized JSON properties are rejected (400), never silently ignored.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Account created",
					content = @Content(schema = @Schema(implementation = AccountResponse.class))),
			@ApiResponse(responseCode = "400", description = "Missing/blank ownerName, malformed currency, "
					+ "or an unrecognized JSON property",
					content = @Content(schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "422", description = "currency is well-formed but not USD "
					+ "(only USD is supported in Phase 1)",
					content = @Content(schema = @Schema(implementation = ApiError.class)))
	})
	public AccountResponse createAccount(@Valid @RequestBody CreateAccountRequest request) {
		return accountService.createCustomerWalletAccount(request);
	}

	@PostMapping("/{id}/deposits")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Deposit USD into a customer wallet",
			description = "Posts a single atomic, balanced double-entry ledger transaction: DEBITs the "
					+ "internal EXTERNAL_FUNDING asset account and CREDITs this customer wallet, for the "
					+ "same amount and currency, in the same database transaction as both accounts' "
					+ "materialized balance updates.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Deposit applied",
					content = @Content(schema = @Schema(implementation = DepositResponse.class))),
			@ApiResponse(responseCode = "400", description = "Missing/non-positive/malformed amount, "
					+ "unsupported precision or scale, malformed currency, or an unrecognized JSON property",
					content = @Content(schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "404", description = "Account not found — including a SYSTEM "
					+ "account id (e.g. EXTERNAL_FUNDING) or any account that is not "
					+ "CUSTOMER/LIABILITY/CUSTOMER_WALLET, treated identically to a nonexistent id",
					content = @Content(schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "422", description = "currency is well-formed but not USD",
					content = @Content(schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "409", description = "The Idempotency-Key was already used for a "
					+ "different deposit or transfer command",
					content = @Content(schema = @Schema(implementation = ApiError.class)))
	})
	public DepositResponse deposit(
			@Parameter(description = "Customer wallet account id", required = true)
			@PathVariable("id") UUID accountId,
			@Valid @RequestBody DepositRequest request,
			@Parameter(description = "Client-supplied key that makes this deposit safe to retry. The first "
					+ "request for a given key executes it and stores the response; every later request "
					+ "with the same key and the same amount/currency/account replays that exact original "
					+ "response instead of creating a new transaction. Reusing the key with a different "
					+ "amount, currency, account, or against the other endpoint (deposit vs. transfer) is a "
					+ "409 conflict.",
					required = true,
					example = "8f14e45f-ceea-467e-bd48-9ffb2f9d1a30",
					schema = @Schema(type = "string", minLength = 1, maxLength = 128,
							pattern = "^[A-Za-z0-9._:-]{1,128}$"))
			@RequestHeader("Idempotency-Key")
			@Pattern(regexp = "^[A-Za-z0-9._:-]{1,128}$",
					message = "must be 1-128 characters from [A-Za-z0-9._:-]")
			String idempotencyKey) {
		return depositService.deposit(accountId, request, idempotencyKey);
	}

	@GetMapping("/{id}/balance")
	@Operation(summary = "Get an account's current balance",
			description = "Returns the persisted materialized account.balance — the same value deposits "
					+ "and transfers maintain atomically — read directly. Never recomputed from the ledger "
					+ "and never cached; a GET request never changes any financial state.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "Current balance",
					content = @Content(schema = @Schema(implementation = AccountBalanceResponse.class))),
			@ApiResponse(responseCode = "404", description = "Account not found — including a SYSTEM "
					+ "account id, treated identically to a nonexistent id",
					content = @Content(schema = @Schema(implementation = ApiError.class)))
	})
	public AccountBalanceResponse getBalance(
			@Parameter(description = "Customer wallet account id", required = true)
			@PathVariable("id") UUID accountId) {
		return accountQueryService.getBalance(accountId);
	}

	@GetMapping("/{id}/transactions")
	@Operation(summary = "Get an account's transaction history",
			description = "Returns this account's own immutable ledger entries only — a deposit credit "
					+ "received by the wallet, USD sent via a transfer (debit), or USD received via a "
					+ "transfer (credit). Never a counterparty's entry, and never an EXTERNAL_FUNDING entry. "
					+ "Ordered newest first: created_at DESC, with the entry's own id as a deterministic "
					+ "tie-breaker for equal timestamps. A GET request never changes any financial state.")
	@ApiResponses({
			// No explicit response schema here -- springdoc infers the full
			// generic PagedResponse<TransactionHistoryItem> shape directly from
			// this method's actual return type. Overriding it with
			// @Schema(implementation = PagedResponse.class) erases the generic
			// type parameter and produces an empty `content` item schema --
			// confirmed empirically, this comment is here to prevent
			// regressing back into that mistake.
			@ApiResponse(responseCode = "200", description = "One page of transaction history"),
			@ApiResponse(responseCode = "400", description = "Malformed page/size, page < 0, size <= 0, "
					+ "or size > 100",
					content = @Content(schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "404", description = "Account not found — including a SYSTEM "
					+ "account id, treated identically to a nonexistent id",
					content = @Content(schema = @Schema(implementation = ApiError.class)))
	})
	public PagedResponse<TransactionHistoryItem> getTransactionHistory(
			@Parameter(description = "Customer wallet account id", required = true)
			@PathVariable("id") UUID accountId,
			@Parameter(description = "Zero-based page number")
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@Parameter(description = "Items per page (1-100)")
			@RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
		return accountQueryService.getTransactionHistory(accountId, page, size);
	}

}
