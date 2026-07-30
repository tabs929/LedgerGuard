package com.tarun.ledgerguard.transfer;

import com.tarun.ledgerguard.common.ApiError;
import com.tarun.ledgerguard.transfer.dto.TransferRequest;
import com.tarun.ledgerguard.transfer.dto.TransferResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/v1/transfers from docs/API_SPEC.md (Task 5). Error handling is
 * intentionally minimal here too: bean-validation failures and
 * unknown-JSON-property rejections are handled by Spring's default
 * exception resolution (400); the domain-specific cases (account not
 * found, currency errors, same-account, insufficient funds) are handled by
 * the shared {@code common.GlobalExceptionHandler}, which
 * AccountController's deposit/creation endpoints also rely on. See
 * docs/ARCHITECTURE.md's "Error Handling" section for the complete
 * mapping (Task 7).
 */
@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Customer-to-customer USD transfers.")
public class TransferController {

	private final TransferService transferService;

	public TransferController(TransferService transferService) {
		this.transferService = transferService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Transfer USD between two customer wallets",
			description = "Posts a single atomic, balanced double-entry ledger transaction: DEBITs the "
					+ "source customer wallet (decreasing its balance) and CREDITs the destination customer "
					+ "wallet (increasing its balance), for the same amount and currency, in the same "
					+ "database transaction as both accounts' materialized balance updates. The combined "
					+ "balance of the two accounts is unchanged. EXTERNAL_FUNDING is never involved. "
					+ "Rejected if the source account's balance is less than the requested amount.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "Transfer applied",
					content = @Content(schema = @Schema(implementation = TransferResponse.class))),
			@ApiResponse(responseCode = "400", description = "Missing source/destination id, "
					+ "missing/non-positive/malformed amount, unsupported precision or scale, malformed "
					+ "currency, or an unrecognized JSON property",
					content = @Content(schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "404", description = "Either account id is not found — including "
					+ "a SYSTEM account id or any account that is not CUSTOMER/LIABILITY/CUSTOMER_WALLET, "
					+ "treated identically to a nonexistent id",
					content = @Content(schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "422", description = "Insufficient funds, currency is well-formed "
					+ "but not USD, or sourceAccountId == destinationAccountId",
					content = @Content(schema = @Schema(implementation = ApiError.class)))
	})
	public TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
		return transferService.transfer(request);
	}

}
