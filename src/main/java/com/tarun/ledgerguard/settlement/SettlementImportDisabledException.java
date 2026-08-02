package com.tarun.ledgerguard.settlement;

/**
 * Thrown when {@code ledgerguard.settlement.import.enabled} is
 * {@code false}. Mapped to a single explicit, documented 503 response by
 * {@code GlobalExceptionHandler} -- every other endpoint is unaffected by
 * this flag.
 */
public class SettlementImportDisabledException extends RuntimeException {

	public SettlementImportDisabledException() {
		super("Settlement import is currently disabled.");
	}

}
