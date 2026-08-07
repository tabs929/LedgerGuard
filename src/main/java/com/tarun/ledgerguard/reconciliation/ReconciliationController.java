package com.tarun.ledgerguard.reconciliation;

import com.tarun.ledgerguard.common.ApiError;
import com.tarun.ledgerguard.common.PagedResponse;
import com.tarun.ledgerguard.security.AuthenticatedPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The only three settlement-reconciliation endpoints: a command and two
 * reads. No update, delete, retry, correction, export, administration, or
 * reconciliation-by-source endpoint exists — Task 15 exposes nothing
 * beyond what the approved design requires.
 */
@Validated
@RestController
@RequestMapping("/api/v1/settlement-imports/{importId}/reconciliation")
public class ReconciliationController {

	private final ReconciliationService service;

	public ReconciliationController(ReconciliationService service) {
		this.service = service;
	}

	@Operation(summary = "Reconcile a settlement import's observations against the ledger",
			description = "Compares every settlement observation first recorded by this import "
					+ "(settlement_record.first_import_id = this import) against LedgerGuard's own "
					+ "ledger. Records and reports the result -- never mutates the ledger, accounts, "
					+ "settlement evidence, or any other financial state. A repeated request for an "
					+ "already-reconciled import returns the existing committed run as a replay.")
	@ApiResponses({
			@ApiResponse(responseCode = "201", description = "A new reconciliation run was recorded.",
					content = @Content(schema = @Schema(implementation = ReconciliationSummaryResponse.class))),
			@ApiResponse(responseCode = "200", description = "This import was already reconciled under the "
					+ "current algorithm version; the original committed run is returned as a replay.",
					content = @Content(schema = @Schema(implementation = ReconciliationSummaryResponse.class))),
			@ApiResponse(responseCode = "400", description = "importId is not a syntactically valid UUID.",
					content = @Content(schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "404", description = "No settlement import exists with this id.",
					content = @Content(schema = @Schema(implementation = ApiError.class)))
	})
	@PostMapping
	public ResponseEntity<ReconciliationSummaryResponse> reconcile(@PathVariable UUID importId,
			@AuthenticationPrincipal Jwt jwt) {
		ReconciliationSummaryResponse response = service.reconcile(importId, AuthenticatedPrincipal.fromJwt(jwt));
		HttpStatus status = response.replayed() ? HttpStatus.OK : HttpStatus.CREATED;
		return ResponseEntity.status(status).body(response);
	}

	@Operation(summary = "Get a settlement import's reconciliation summary",
			description = "Returns the existing committed reconciliation run for this import. Does not "
					+ "trigger reconciliation -- use POST for that.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "The committed reconciliation run.",
					content = @Content(schema = @Schema(implementation = ReconciliationSummaryResponse.class))),
			@ApiResponse(responseCode = "400", description = "importId is not a syntactically valid UUID.",
					content = @Content(schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "404", description = "No settlement import exists with this id, or it "
					+ "exists but has never been reconciled.",
					content = @Content(schema = @Schema(implementation = ApiError.class)))
	})
	@GetMapping
	public ReconciliationSummaryResponse getRun(@PathVariable UUID importId, @AuthenticationPrincipal Jwt jwt) {
		return service.getRun(importId, AuthenticatedPrincipal.fromJwt(jwt));
	}

	@Operation(summary = "Get one reconciliation run's item-level results",
			description = "Paginated, ordered by the originating settlement observation's position in its "
					+ "source CSV file (source_row_number ascending) -- a stable, deterministic order.")
	@ApiResponses({
			@ApiResponse(responseCode = "200", description = "One page of reconciliation results.",
					content = @Content(schema = @Schema(implementation = PagedResponse.class))),
			@ApiResponse(responseCode = "400", description = "importId is not a syntactically valid UUID, or "
					+ "page/size is malformed or out of bounds.",
					content = @Content(schema = @Schema(implementation = ApiError.class))),
			@ApiResponse(responseCode = "404", description = "No settlement import exists with this id, or it "
					+ "exists but has never been reconciled.",
					content = @Content(schema = @Schema(implementation = ApiError.class)))
	})
	@GetMapping("/results")
	public PagedResponse<ReconciliationResultResponse> getResults(
			@PathVariable UUID importId,
			@RequestParam(name = "page", defaultValue = "0") @Min(0) int page,
			@RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size,
			@AuthenticationPrincipal Jwt jwt) {
		return service.getResults(importId, AuthenticatedPrincipal.fromJwt(jwt), page, size);
	}

}
