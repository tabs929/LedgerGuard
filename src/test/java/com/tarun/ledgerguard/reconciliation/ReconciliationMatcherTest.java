package com.tarun.ledgerguard.reconciliation;

import com.tarun.ledgerguard.account.AccountCategory;
import com.tarun.ledgerguard.account.AccountClass;
import com.tarun.ledgerguard.account.AccountPurpose;
import com.tarun.ledgerguard.ledger.LedgerEntryType;
import com.tarun.ledgerguard.ledger.TransactionType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationMatcherTest {

	private static final UUID TXN_ID = UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6");
	private static final UUID RECORD_ID = UUID.randomUUID();

	private SettlementObservation observation(String amount, String currency) {
		return new SettlementObservation(RECORD_ID, TXN_ID, new BigDecimal(amount), currency, 1);
	}

	private LedgerEntryView debit(String amount, String currency, AccountCategory category, AccountClass accountClass,
			AccountPurpose purpose) {
		return new LedgerEntryView(LedgerEntryType.DEBIT, new BigDecimal(amount), currency, category, accountClass, purpose);
	}

	private LedgerEntryView credit(String amount, String currency, AccountCategory category, AccountClass accountClass,
			AccountPurpose purpose) {
		return new LedgerEntryView(LedgerEntryType.CREDIT, new BigDecimal(amount), currency, category, accountClass, purpose);
	}

	private LedgerEntryView validDebit(String amount, String currency) {
		return debit(amount, currency, AccountCategory.SYSTEM, AccountClass.ASSET, AccountPurpose.EXTERNAL_FUNDING);
	}

	private LedgerEntryView validCredit(String amount, String currency) {
		return credit(amount, currency, AccountCategory.CUSTOMER, AccountClass.LIABILITY, AccountPurpose.CUSTOMER_WALLET);
	}

	private LedgerTransactionView deposit(LedgerEntryView... entries) {
		return new LedgerTransactionView(TXN_ID, TransactionType.DEPOSIT, List.of(entries));
	}

	private LedgerTransactionView transfer(LedgerEntryView... entries) {
		return new LedgerTransactionView(TXN_ID, TransactionType.TRANSFER, List.of(entries));
	}

	// -- unknown transaction ------------------------------------------------

	@Test
	void classifiesAsNotFoundWhenNoTransactionExists() {
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("100.00", "USD"), null);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.INTERNAL_TRANSACTION_NOT_FOUND);
		assertThat(result.internalAmount()).isNull();
		assertThat(result.internalCurrency()).isNull();
	}

	// -- eligibility ----------------------------------------------------------

	@Test
	void classifiesATransferAsIneligible() {
		LedgerTransactionView view = transfer(
				debit("100.0000", "USD", AccountCategory.CUSTOMER, AccountClass.LIABILITY, AccountPurpose.CUSTOMER_WALLET),
				credit("100.0000", "USD", AccountCategory.CUSTOMER, AccountClass.LIABILITY, AccountPurpose.CUSTOMER_WALLET));
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("100.00", "USD"), view);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.INELIGIBLE_TRANSACTION_TYPE);
		assertThat(result.internalAmount()).isNull();
		assertThat(result.internalCurrency()).isNull();
	}

	// -- matched, exact decimal comparison -----------------------------------

	@Test
	void classifiesAsMatchedWhenAmountAndCurrencyAgree() {
		LedgerTransactionView view = deposit(validDebit("100.0000", "USD"), validCredit("100.0000", "USD"));
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("100.00", "USD"), view);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.MATCHED);
		assertThat(result.internalAmount()).isEqualByComparingTo("100.0000");
		assertThat(result.internalCurrency()).isEqualTo("USD");
	}

	@Test
	void twoEqualMonetaryValuesAtDifferentScalesMatch() {
		// reported: NUMERIC(19,2); internal: NUMERIC(19,4) -- numerically
		// equal, textually/scale different. compareTo, never equals.
		LedgerTransactionView view = deposit(validDebit("55.2500", "USD"), validCredit("55.2500", "USD"));
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("55.25", "USD"), view);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.MATCHED);
		assertThat(new BigDecimal("55.25")).isNotEqualTo(new BigDecimal("55.2500")); // different scale, same value
		assertThat(new BigDecimal("55.25")).isEqualByComparingTo(new BigDecimal("55.2500"));
	}

	// -- mismatches -------------------------------------------------------------

	@Test
	void classifiesAmountOnlyMismatch() {
		LedgerTransactionView view = deposit(validDebit("100.0000", "USD"), validCredit("100.0000", "USD"));
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("50.00", "USD"), view);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.AMOUNT_MISMATCH);
		assertThat(result.internalAmount()).isEqualByComparingTo("100.0000");
		assertThat(result.internalCurrency()).isEqualTo("USD");
	}

	@Test
	void classifiesCurrencyOnlyMismatch() {
		LedgerTransactionView view = deposit(validDebit("100.0000", "EUR"), validCredit("100.0000", "EUR"));
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("100.00", "USD"), view);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.CURRENCY_MISMATCH);
	}

	@Test
	void classifiesCombinedAmountAndCurrencyMismatch() {
		LedgerTransactionView view = deposit(validDebit("100.0000", "EUR"), validCredit("100.0000", "EUR"));
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("50.00", "USD"), view);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.AMOUNT_AND_CURRENCY_MISMATCH);
	}

	// -- internal ledger inconsistency ---------------------------------------

	@Test
	void classifiesAsInconsistentWhenEntryCountIsWrong() {
		LedgerTransactionView view = deposit(validDebit("100.0000", "USD"));
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("100.00", "USD"), view);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.INTERNAL_LEDGER_INCONSISTENT);
		assertThat(result.internalAmount()).isNull();
		assertThat(result.internalCurrency()).isNull();
	}

	@Test
	void classifiesAsInconsistentWhenDebitAccountPurposeIsWrong() {
		LedgerTransactionView view = deposit(
				debit("100.0000", "USD", AccountCategory.CUSTOMER, AccountClass.LIABILITY, AccountPurpose.CUSTOMER_WALLET),
				validCredit("100.0000", "USD"));
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("100.00", "USD"), view);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.INTERNAL_LEDGER_INCONSISTENT);
	}

	@Test
	void classifiesAsInconsistentWhenCreditAccountClassificationIsWrong() {
		LedgerTransactionView view = deposit(
				validDebit("100.0000", "USD"),
				credit("100.0000", "USD", AccountCategory.SYSTEM, AccountClass.ASSET, AccountPurpose.EXTERNAL_FUNDING));
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("100.00", "USD"), view);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.INTERNAL_LEDGER_INCONSISTENT);
	}

	@Test
	void classifiesAsInconsistentWhenDebitAndCreditDirectionIsReversed() {
		// Both entries carry the correct account taxonomy for their
		// respective sides, but the entry_type values are swapped --
		// a CREDIT posted to EXTERNAL_FUNDING and a DEBIT posted to the
		// customer wallet, the reverse of a valid deposit.
		LedgerTransactionView view = deposit(
				credit("100.0000", "USD", AccountCategory.SYSTEM, AccountClass.ASSET, AccountPurpose.EXTERNAL_FUNDING),
				debit("100.0000", "USD", AccountCategory.CUSTOMER, AccountClass.LIABILITY, AccountPurpose.CUSTOMER_WALLET));
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("100.00", "USD"), view);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.INTERNAL_LEDGER_INCONSISTENT);
	}

	@Test
	void classifiesAsInconsistentWhenDebitAndCreditAmountsDisagree() {
		LedgerTransactionView view = deposit(validDebit("100.0000", "USD"), validCredit("99.0000", "USD"));
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("100.00", "USD"), view);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.INTERNAL_LEDGER_INCONSISTENT);
	}

	@Test
	void classifiesAsInconsistentWhenDebitAndCreditCurrenciesDisagree() {
		LedgerTransactionView view = deposit(validDebit("100.0000", "USD"), validCredit("100.0000", "EUR"));
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("100.00", "USD"), view);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.INTERNAL_LEDGER_INCONSISTENT);
	}

	@Test
	void classifiesAsInconsistentWhenAnAmountIsNonPositive() {
		LedgerTransactionView view = deposit(validDebit("0.0000", "USD"), validCredit("0.0000", "USD"));
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("100.00", "USD"), view);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.INTERNAL_LEDGER_INCONSISTENT);
	}

	@Test
	void classifiesAsInconsistentWhenBothEntriesAreDebits() {
		LedgerTransactionView view = deposit(
				debit("100.0000", "USD", AccountCategory.SYSTEM, AccountClass.ASSET, AccountPurpose.EXTERNAL_FUNDING),
				debit("100.0000", "USD", AccountCategory.CUSTOMER, AccountClass.LIABILITY, AccountPurpose.CUSTOMER_WALLET));
		ReconciliationClassification result = ReconciliationMatcher.classify(observation("100.00", "USD"), view);
		assertThat(result.outcome()).isEqualTo(ReconciliationOutcome.INTERNAL_LEDGER_INCONSISTENT);
	}

	// -- determinism ------------------------------------------------------------

	@Test
	void classificationIsDeterministic() {
		LedgerTransactionView view = deposit(validDebit("100.0000", "USD"), validCredit("100.0000", "USD"));
		SettlementObservation observation = observation("100.00", "USD");
		ReconciliationClassification first = ReconciliationMatcher.classify(observation, view);
		ReconciliationClassification second = ReconciliationMatcher.classify(observation, view);
		assertThat(first).isEqualTo(second);
	}

}
