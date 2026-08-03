package com.tarun.ledgerguard.reconciliation;

import java.util.UUID;

/**
 * Thrown when a path {@code importId} does not correspond to any
 * {@code settlement_import} row. Mapped to 404. Distinct from
 * {@link ReconciliationNotFoundException} (the import exists but has
 * never been reconciled) — the two are different failure conditions with
 * different, distinct messages, per docs/API_SPEC.md.
 */
public class SettlementImportNotFoundException extends RuntimeException {

	public SettlementImportNotFoundException(UUID importId) {
		super("Settlement import not found: " + importId);
	}

}
