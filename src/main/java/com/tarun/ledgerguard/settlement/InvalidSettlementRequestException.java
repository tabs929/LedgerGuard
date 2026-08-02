package com.tarun.ledgerguard.settlement;

/**
 * Thrown for every 400-level settlement-import validation failure: an
 * invalid {@code source} value, an empty/header-only file, an invalid CSV
 * header, or an invalid row. The message always consists only of a fixed
 * string plus a row number and/or a hardcoded field name -- never the
 * submitted value itself (see docs/API_SPEC.md's "Safe row-validation
 * errors" section).
 */
public class InvalidSettlementRequestException extends RuntimeException {

	public InvalidSettlementRequestException(String message) {
		super(message);
	}

}
