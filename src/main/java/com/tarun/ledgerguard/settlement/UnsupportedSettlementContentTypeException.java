package com.tarun.ledgerguard.settlement;

/**
 * Thrown when the uploaded file part carries a content type outside the
 * small allow-list {@code SettlementImportService} accepts. Mapped to 415.
 * The rejected content-type value itself is not echoed back -- it is a
 * client-supplied header value, not something this API should reflect.
 */
public class UnsupportedSettlementContentTypeException extends RuntimeException {

	public UnsupportedSettlementContentTypeException() {
		super("Unsupported content type for settlement CSV upload.");
	}

}
