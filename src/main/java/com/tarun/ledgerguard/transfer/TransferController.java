package com.tarun.ledgerguard.transfer;

import com.tarun.ledgerguard.transfer.dto.TransferRequest;
import com.tarun.ledgerguard.transfer.dto.TransferResponse;
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
 * the shared {@code common.AccountAndTransferExceptionHandler}, which
 * AccountController's deposit/creation endpoints also rely on. The full
 * cross-cutting error-response framework remains Task 7's responsibility.
 */
@RestController
@RequestMapping("/api/v1/transfers")
public class TransferController {

	private final TransferService transferService;

	public TransferController(TransferService transferService) {
		this.transferService = transferService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
		return transferService.transfer(request);
	}

}
