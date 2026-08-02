package com.tarun.ledgerguard.settlement;

/**
 * Thrown when a row's (normalized_source, external_reference) identity
 * already exists with different business content (a different
 * transaction id, amount, currency, settled timestamp, or row hash).
 * Rolls back the whole import (see {@code SettlementImportProcessor}) and
 * is mapped to 409. Only the safe, integer row number is included in the
 * message -- never the row's own external_reference or other field
 * values, which are untrusted CSV content (see docs/API_SPEC.md's "CSV
 * Formula-Injection Handling" section: raw field values must never be
 * reflected into error messages).
 */
public class SettlementConflictException extends RuntimeException {

	public SettlementConflictException(int sourceRowNumber) {
		super("Conflicting settlement observation for row " + sourceRowNumber
				+ ": identity already exists with different data.");
	}

}
