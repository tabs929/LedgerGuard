package com.tarun.ledgerguard.transfer.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
		UUID sourceAccountId,

		@NotNull
		UUID destinationAccountId,

		@NotNull
		@Positive
		@Digits(integer = 15, fraction = 4, message = "amount must have at most 15 integer digits and 4 decimal digits")
		BigDecimal amount,

		@NotBlank
		@Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a 3-letter ISO 4217 code")
		String currency

) {
}
