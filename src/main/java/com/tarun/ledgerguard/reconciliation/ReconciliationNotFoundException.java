package com.tarun.ledgerguard.reconciliation;

import java.util.UUID;

/**
 * Thrown when {@code importId} refers to a real {@code settlement_import}
 * that has never been reconciled (no {@code reconciliation_run} exists
 * for it yet). Mapped to 404, with a message distinct from
 * {@link SettlementImportNotFoundException} so a caller can tell "this
 * import doesn't exist" apart from "this import exists but has not been
 * reconciled."
 */
public class ReconciliationNotFoundException extends RuntimeException {

	public ReconciliationNotFoundException(UUID importId) {
		super("No reconciliation run exists for settlement import: " + importId);
	}

}
