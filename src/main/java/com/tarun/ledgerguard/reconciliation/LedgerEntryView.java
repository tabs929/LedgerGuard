package com.tarun.ledgerguard.reconciliation;

import com.tarun.ledgerguard.account.AccountCategory;
import com.tarun.ledgerguard.account.AccountClass;
import com.tarun.ledgerguard.account.AccountPurpose;
import com.tarun.ledgerguard.ledger.LedgerEntryType;

import java.math.BigDecimal;

/**
 * One ledger entry's business fields, plus its owning account's taxonomy
 * (bulk-loaded alongside it — see {@code LedgerDataLoader}), exactly what
 * {@link ReconciliationMatcher} needs to validate a deposit's posting
 * structure without a second round trip per entry.
 */
record LedgerEntryView(
		LedgerEntryType entryType,
		BigDecimal amount,
		String currency,
		AccountCategory accountCategory,
		AccountClass accountClass,
		AccountPurpose accountPurpose) {
}
