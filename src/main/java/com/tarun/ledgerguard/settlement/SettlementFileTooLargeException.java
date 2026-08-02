package com.tarun.ledgerguard.settlement;

/**
 * Thrown when the uploaded file exceeds
 * {@code ledgerguard.settlement.import.max-file-size-bytes}, enforced
 * inside {@code SettlementImportService} independently of Spring's own
 * outer {@code spring.servlet.multipart.max-file-size} boundary. Mapped
 * to 413.
 */
public class SettlementFileTooLargeException extends RuntimeException {

	public SettlementFileTooLargeException(long maxFileSizeBytes) {
		super("Uploaded file exceeds the maximum allowed size of " + maxFileSizeBytes + " bytes.");
	}

}
