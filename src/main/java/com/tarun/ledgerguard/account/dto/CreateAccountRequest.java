package com.tarun.ledgerguard.account.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Public request contract for POST /api/v1/accounts, per docs/API_SPEC.md.
 * Deliberately has no field for account category, class, purpose, balance,
 * id, createdAt, or system-account status — those are server-assigned and
 * cannot be influenced by the client. Unknown JSON properties (an attempt to
 * smuggle in one of those fields) are rejected outright, not silently
 * ignored — see {@code spring.jackson.deserialization.fail-on-unknown-properties}
 * in application.yml, reinforced here per-DTO.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record CreateAccountRequest(

		@NotBlank
		@Size(max = 255)
		String ownerName,

		@NotBlank
		@Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a 3-letter ISO 4217 code")
		String currency

) {
}
