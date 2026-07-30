package com.tarun.ledgerguard.account.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

/**
 * Public request contract for POST /api/v1/accounts/{id}/deposits, per
 * docs/API_SPEC.md. Deliberately has no field for transaction id/type/
 * status, entry direction, the funding account, ledger-entry ids, account
 * balances, timestamps, or account taxonomy — those are server-assigned
 * and cannot be influenced by the client. Unknown JSON properties are
 * rejected outright, not silently ignored — see
 * {@code spring.jackson.deserialization.fail-on-unknown-properties} in
 * application.yml, reinforced here per-DTO.
 *
 * <p>{@code amount} accepts up to 15 integer digits and exactly 4 decimal
 * digits, matching the database's {@code NUMERIC(19,4)} column exactly —
 * an out-of-range value is rejected here (400) rather than surfacing as a
 * raw SQL numeric-overflow error.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record DepositRequest(

		@NotNull
		@Positive
		@Digits(integer = 15, fraction = 4, message = "amount must have at most 15 integer digits and 4 decimal digits")
		@Schema(type = "string", example = "100.00",
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
