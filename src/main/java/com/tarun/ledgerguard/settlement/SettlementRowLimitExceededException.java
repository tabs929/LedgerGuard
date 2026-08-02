package com.tarun.ledgerguard.settlement;

/**
 * Thrown when a CSV file's data-row count exceeds
 * {@code ledgerguard.settlement.import.max-row-count}, detected while
 * parsing (before any persistence is attempted). Mapped to 413, the same
 * status as {@link SettlementFileTooLargeException} -- both represent
 * "too much data for one import," and using one consistent status for
 * both is simpler than distinguishing 413 from 422 for this case.
 */
public class SettlementRowLimitExceededException extends RuntimeException {

	public SettlementRowLimitExceededException(int maxRowCount) {
		super("CSV file exceeds the maximum allowed row count of " + maxRowCount + ".");
	}

}
