package com.tarun.ledgerguard.security.dto;

import jakarta.validation.constraints.NotBlank;

public record TokenRequest(
		@NotBlank String username,
		@NotBlank String password) {
}
