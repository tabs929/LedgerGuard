package com.tarun.ledgerguard.settlement;

import com.tarun.ledgerguard.security.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Validates that the multipart request carries the two required parts and
 * delegates everything else to {@link SettlementImportService}. Contains
 * no CSV parsing or SQL -- {@code source}/{@code file} presence is
 * enforced by Spring's own required-parameter binding (a missing part
 * throws before this method body runs; see
 * {@code GlobalExceptionHandler#handleMissingParameter}).
 *
 * <p>Deliberately the only endpoint under {@code /api/v1/settlement-imports}
 * -- no list, get, retry, update, delete, reconciliation, or
 * administration endpoint exists for {@code settlement_import} or
 * {@code settlement_record} (see docs/API_SPEC.md).
 */
@RestController
@RequestMapping("/api/v1/settlement-imports")
public class SettlementImportController {

	private final SettlementImportService service;

	public SettlementImportController(SettlementImportService service) {
		this.service = service;
	}

	@Operation(summary = "Import an external settlement CSV file",
			description = "Records immutable external settlement observations reported by a bank or "
					+ "payment processor (Task 14). Does not reconcile them against LedgerGuard's own "
					+ "ledger (Task 15) and never mutates any account, ledger, outbox, processed-event, "
					+ "or idempotency state.")
	@io.swagger.v3.oas.annotations.responses.ApiResponses({
			@ApiResponse(responseCode = "201", description = "A new import was recorded.",
					content = @Content(schema = @Schema(implementation = SettlementImportResponse.class))),
			@ApiResponse(responseCode = "200", description = "The exact same file bytes were already imported "
					+ "from this source; the original committed result is returned as a replay (replayed=true).",
					content = @Content(schema = @Schema(implementation = SettlementImportResponse.class))),
			@ApiResponse(responseCode = "400", description = "Malformed request, empty/header-only file, "
					+ "invalid CSV header/row, or an invalid source value.",
					content = @Content(schema = @Schema(implementation = com.tarun.ledgerguard.common.ApiError.class))),
			@ApiResponse(responseCode = "409", description = "A row's (source, external_reference) identity "
					+ "conflicts with a previously stored settlement observation.",
					content = @Content(schema = @Schema(implementation = com.tarun.ledgerguard.common.ApiError.class))),
			@ApiResponse(responseCode = "413", description = "The file, or its row count, exceeds the configured limit.",
					content = @Content(schema = @Schema(implementation = com.tarun.ledgerguard.common.ApiError.class))),
			@ApiResponse(responseCode = "415", description = "Unsupported file content type.",
					content = @Content(schema = @Schema(implementation = com.tarun.ledgerguard.common.ApiError.class)))
	})
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<SettlementImportResponse> importSettlementFile(
			@Parameter(description = "External provider identifier", required = true)
			@RequestParam("source") String source,
			@Parameter(description = "Settlement CSV file", required = true,
					schema = @Schema(type = "string", format = "binary"))
			@RequestParam("file") MultipartFile file,
			@AuthenticationPrincipal Jwt jwt) {
		SettlementImportOutcome outcome = service.importFile(source, file, AuthenticatedPrincipal.fromJwt(jwt));
		HttpStatus status = outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
		return ResponseEntity.status(status).body(SettlementImportResponse.from(outcome));
	}

}
