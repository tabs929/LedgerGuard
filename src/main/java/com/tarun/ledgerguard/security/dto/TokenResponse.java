package com.tarun.ledgerguard.security.dto;

public record TokenResponse(
		String accessToken,
		String tokenType,
		long expiresInSeconds) {
}
