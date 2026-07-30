package com.tarun.ledgerguard.account;

import java.util.UUID;

/**
 * Thrown when a transfer's source and destination account ids are equal.
 * Per docs/API_SPEC.md, mapped to 422.
 */
public class SameAccountTransferException extends RuntimeException {

	public SameAccountTransferException(UUID accountId) {
		super("Source and destination account must differ: " + accountId);
	}

}
