package com.tarun.ledgerguard.security;

/**
 * Thrown by {@link TokenController} for both "no such username" and
 * "wrong password" — deliberately a single exception/message so a client
 * can never distinguish which one occurred.
 */
public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("Invalid username or password.");
	}

}
