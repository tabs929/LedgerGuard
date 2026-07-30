package com.tarun.ledgerguard.transfer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public request contract for POST /api/v1/transfers, per
 * docs/API_SPEC.md. Deliberately has no field for transaction id/type/
 * status, entry direction, ledger-entry ids, account balances, timestamps,
 * or account taxonomy — those are server-assigned and cannot be influenced
 * by the client. Unknown JSON properties are rejected outright, not
 * silently ignored — see
 * {@code spring.jackson.deserialization.fail-on-unknown-properties} in
 * application.yml, reinforced here per-DTO.
 *
 * <p>{@code amount} accepts up to 15 integer digits and exactly 4 decimal
 * digits, matching the database's {@code NUMERIC(19,4)} column exactly,
 * same as {@code DepositRequest}.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record TransferRequest(

		@NotNull
		@Schema(example = "3fa85f64-5717-4562-b3fc-2c963f66afa6", requiredMode = Schema.RequiredMode.REQUIRED)
		UUID sourceAccountId,

		@NotNull
		@Schema(example = "9c858901-8a57-4791-81fe-4c455b099bc9", requiredMode = Schema.RequiredMode.REQUIRED)
		UUID destinationAccountId,

		@NotNull
		@Positive
		@Digits(integer = 15, fraction = 4, message = "amount must have at most 15 integer digits and 4 decimal digits")
		@Schema(type = "string", example = "50.00",
				description = "Decimal amount, greater than 0, at most 15 integer digits and 4 decimal digits",
				requiredMode = Schema.RequiredMode.REQUIRED)
		BigDecimal amount,

		@NotBlank
		@Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a 3-letter ISO 4217 code")
		@Schema(example = "USD", description = "ISO 4217 code; only USD is supported in Phase 1",
				requiredMode = Schema.RequiredMode.REQUIRED)
		String currency

) {
}
