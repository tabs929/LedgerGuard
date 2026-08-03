package com.tarun.ledgerguard.reconciliation;

import com.tarun.ledgerguard.account.AccountCategory;
import com.tarun.ledgerguard.account.AccountClass;
import com.tarun.ledgerguard.account.AccountPurpose;
import com.tarun.ledgerguard.ledger.LedgerEntryType;
import com.tarun.ledgerguard.ledger.TransactionType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * The complete Task 15 matching algorithm — a pure function from one
 * {@link SettlementObservation} plus its (possibly absent) bulk-loaded
 * {@link LedgerTransactionView} to exactly one {@link ReconciliationClassification}.
 * No database access, no I/O, fully deterministic: the same inputs always
 * produce the same output. See docs/ARCHITECTURE.md's "Settlement
 * Reconciliation" section for the full precedence rationale.
 *
 * <p><b>Only {@link TransactionType#DEPOSIT} is settlement-eligible.</b>
 * Deposits are the only transaction type that crosses the system boundary
 * (DEBIT {@code EXTERNAL_FUNDING}) — transfers move value only between two
 * internal customer accounts, so there is no reason an external source
 * would ever report one. A reported transfer is classified
 * {@link ReconciliationOutcome#INELIGIBLE_TRANSACTION_TYPE}, not compared
 * against any amount/currency.
 *
 * <p>Classification precedence, evaluated in this exact order (each step
 * stops and returns as soon as it applies):
 * <ol>
 *   <li>No {@code ledger_transaction} found for the reported id →
 *       {@link ReconciliationOutcome#INTERNAL_TRANSACTION_NOT_FOUND}.
 *   <li>Found, but not a {@code DEPOSIT} →
 *       {@link ReconciliationOutcome#INELIGIBLE_TRANSACTION_TYPE}.
 *   <li>A {@code DEPOSIT} whose posting structure fails validation (see
 *       {@link #validateDepositStructure}) →
 *       {@link ReconciliationOutcome#INTERNAL_LEDGER_INCONSISTENT}.
 *   <li>A structurally valid deposit: compare the reported amount/currency
 *       (via {@link BigDecimal#compareTo}, never {@code equals} — the two
 *       sides have different scales, {@code NUMERIC(19,2)} reported vs.
 *       {@code NUMERIC(19,4)} internal — and never a {@code float}/
 *       {@code double}) against the validated internal amount/currency,
 *       producing {@link ReconciliationOutcome#MATCHED},
 *       {@link ReconciliationOutcome#AMOUNT_MISMATCH},
 *       {@link ReconciliationOutcome#CURRENCY_MISMATCH}, or
 *       {@link ReconciliationOutcome#AMOUNT_AND_CURRENCY_MISMATCH}.
 * </ol>
 */
final class ReconciliationMatcher {

	private ReconciliationMatcher() {
	}

	static ReconciliationClassification classify(SettlementObservation observation,
			LedgerTransactionView transactionView) {
		if (transactionView == null) {
			return new ReconciliationClassification(ReconciliationOutcome.INTERNAL_TRANSACTION_NOT_FOUND, null, null);
		}
		if (transactionView.transactionType() != TransactionType.DEPOSIT) {
			return new ReconciliationClassification(ReconciliationOutcome.INELIGIBLE_TRANSACTION_TYPE, null, null);
		}

		Optional<InternalValue> internal = validateDepositStructure(transactionView);
		if (internal.isEmpty()) {
			return new ReconciliationClassification(ReconciliationOutcome.INTERNAL_LEDGER_INCONSISTENT, null, null);
		}

		InternalValue value = internal.get();
		boolean amountMatches = observation.reportedAmount().compareTo(value.amount()) == 0;
		boolean currencyMatches = observation.reportedCurrency().equals(value.currency());

		ReconciliationOutcome outcome;
		if (amountMatches && currencyMatches) {
			outcome = ReconciliationOutcome.MATCHED;
		} else if (!amountMatches && !currencyMatches) {
			outcome = ReconciliationOutcome.AMOUNT_AND_CURRENCY_MISMATCH;
		} else if (!amountMatches) {
			outcome = ReconciliationOutcome.AMOUNT_MISMATCH;
		} else {
			outcome = ReconciliationOutcome.CURRENCY_MISMATCH;
		}
		return new ReconciliationClassification(outcome, value.amount(), value.currency());
	}

	/**
	 * A valid deposit posting is exactly: two entries, one DEBIT against a
	 * SYSTEM/ASSET/EXTERNAL_FUNDING account and one CREDIT against a
	 * CUSTOMER/LIABILITY/CUSTOMER_WALLET account, both with a positive,
	 * equal amount and an equal currency. Every one of these is already
	 * supposed to be guaranteed by {@code DepositService} and (for amount
	 * positivity and account taxonomy) by V1's own CHECK constraints — this
	 * revalidates all of it independently rather than trusting it, since
	 * Task 15's whole purpose is to detect exactly this kind of internal
	 * data-integrity failure if it were ever to occur.
	 */
	private static Optional<InternalValue> validateDepositStructure(LedgerTransactionView transactionView) {
		List<LedgerEntryView> entries = transactionView.entries();
		if (entries.size() != 2) {
			return Optional.empty();
		}

		LedgerEntryView debit = null;
		LedgerEntryView credit = null;
		for (LedgerEntryView entry : entries) {
			if (entry.entryType() == LedgerEntryType.DEBIT) {
				debit = entry;
			} else if (entry.entryType() == LedgerEntryType.CREDIT) {
				credit = entry;
			}
		}
		if (debit == null || credit == null) {
			return Optional.empty();
		}

		if (debit.accountCategory() != AccountCategory.SYSTEM || debit.accountClass() != AccountClass.ASSET
				|| debit.accountPurpose() != AccountPurpose.EXTERNAL_FUNDING) {
			return Optional.empty();
		}
		if (credit.accountCategory() != AccountCategory.CUSTOMER || credit.accountClass() != AccountClass.LIABILITY
				|| credit.accountPurpose() != AccountPurpose.CUSTOMER_WALLET) {
			return Optional.empty();
		}

		if (debit.amount().signum() <= 0 || credit.amount().signum() <= 0) {
			return Optional.empty();
		}
		if (debit.amount().compareTo(credit.amount()) != 0) {
			return Optional.empty();
		}
		if (!debit.currency().equals(credit.currency())) {
			return Optional.empty();
		}

		return Optional.of(new InternalValue(debit.amount(), debit.currency()));
	}

	private record InternalValue(BigDecimal amount, String currency) {
	}

}
