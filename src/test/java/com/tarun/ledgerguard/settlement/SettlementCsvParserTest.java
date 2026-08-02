package com.tarun.ledgerguard.settlement;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementCsvParserTest {

	private static final String HEADER = "external_reference,transaction_id,amount,currency,settled_at";
	private static final String TXN_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
	private static final String VALID_ROW = "EXT-001," + TXN_ID + ",100.00,USD,2026-07-15T10:00:00Z";

	private final SettlementCsvParser parser = new SettlementCsvParser();

	private byte[] utf8(String content) {
		return content.getBytes(StandardCharsets.UTF_8);
	}

	private byte[] withBom(byte[] content) {
		byte[] bom = { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF };
		byte[] result = new byte[bom.length + content.length];
		System.arraycopy(bom, 0, result, 0, bom.length);
		System.arraycopy(content, 0, result, bom.length, content.length);
		return result;
	}

	private SettlementImportProperties defaultProperties() {
		return new SettlementImportProperties();
	}

	// -- valid parsing --------------------------------------------------

	@Test
	void parsesASingleValidRow() {
		List<SettlementCsvRow> rows = parser.parse(utf8(HEADER + "\n" + VALID_ROW + "\n"), "acme-bank", defaultProperties());
		assertThat(rows).hasSize(1);
		SettlementCsvRow row = rows.get(0);
		assertThat(row.sourceRowNumber()).isEqualTo(1);
		assertThat(row.externalReference()).isEqualTo("EXT-001");
		assertThat(row.transactionId()).hasToString(TXN_ID);
		assertThat(row.amount()).isEqualByComparingTo("100.00");
		assertThat(row.currency()).isEqualTo("USD");
		assertThat(row.rowHash()).matches("^[0-9a-f]{64}$");
	}

	@Test
	void assignsOneBasedSourceRowNumbersExcludingTheHeader() {
		String content = HEADER + "\n" + VALID_ROW + "\nEXT-002," + TXN_ID + ",50.00,USD,2026-07-15T10:00:00Z\n";
		List<SettlementCsvRow> rows = parser.parse(utf8(content), "acme-bank", defaultProperties());
		assertThat(rows).extracting(SettlementCsvRow::sourceRowNumber).containsExactly(1, 2);
	}

	@Test
	void supportsLfLineEndings() {
		String content = HEADER + "\n" + VALID_ROW + "\n";
		assertThat(parser.parse(utf8(content), "acme-bank", defaultProperties())).hasSize(1);
	}

	@Test
	void supportsCrlfLineEndings() {
		String content = HEADER + "\r\n" + VALID_ROW + "\r\n";
		assertThat(parser.parse(utf8(content), "acme-bank", defaultProperties())).hasSize(1);
	}

	@Test
	void acceptsAnOptionalUtf8Bom() {
		byte[] content = withBom(utf8(HEADER + "\n" + VALID_ROW + "\n"));
		assertThat(parser.parse(content, "acme-bank", defaultProperties())).hasSize(1);
	}

	@Test
	void supportsQuotedFieldsWithEmbeddedCommas() {
		String content = HEADER + "\n\"EXT,001\"," + TXN_ID + ",100.00,USD,2026-07-15T10:00:00Z\n";
		List<SettlementCsvRow> rows = parser.parse(utf8(content), "acme-bank", defaultProperties());
		assertThat(rows.get(0).externalReference()).isEqualTo("EXT,001");
	}

	@Test
	void supportsEscapedQuotesInsideQuotedFields() {
		String content = HEADER + "\n\"EXT\"\"001\"," + TXN_ID + ",100.00,USD,2026-07-15T10:00:00Z\n";
		List<SettlementCsvRow> rows = parser.parse(utf8(content), "acme-bank", defaultProperties());
		assertThat(rows.get(0).externalReference()).isEqualTo("EXT\"001");
	}

	@Test
	void supportsEmbeddedNewlinesInsideQuotedFieldsAtTheCsvStructuralLevel() {
		// A raw newline inside a quoted field must not be treated as a
		// record separator (which would misparse this as extra/missing
		// rows or columns) -- but external_reference's own "no control
		// characters" rule (a raw newline included) still legitimately
		// rejects the resulting value. This proves the CSV layer parsed
		// one single five-column row (the failure is the specific
		// external_reference control-character rule, not a structural
		// "wrong column count" or "malformed CSV" error).
		String content = HEADER + "\n\"EXT\n001\"," + TXN_ID + ",100.00,USD,2026-07-15T10:00:00Z\n";
		assertThatThrownBy(() -> parser.parse(utf8(content), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class)
				.hasMessageContaining("control characters");
	}

	@Test
	void ignoresCompletelyBlankPhysicalLines() {
		String content = HEADER + "\n" + VALID_ROW + "\n\n";
		assertThat(parser.parse(utf8(content), "acme-bank", defaultProperties())).hasSize(1);
	}

	// -- structural rejection --------------------------------------------

	@Test
	void rejectsAnEmptyFile() {
		assertThatThrownBy(() -> parser.parse(utf8(""), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsAHeaderOnlyFile() {
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsAnUnknownColumn() {
		String header = "external_reference,transaction_id,amount,currency,settled_at,extra_column";
		String row = VALID_ROW + ",unexpected";
		assertThatThrownBy(() -> parser.parse(utf8(header + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsAMissingColumn() {
		String header = "external_reference,transaction_id,amount,currency";
		assertThatThrownBy(() -> parser.parse(utf8(header + "\nEXT-001," + TXN_ID + ",100.00,USD\n"), "acme-bank",
				defaultProperties())).isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsADuplicateHeaderColumn() {
		String header = "external_reference,external_reference,amount,currency,settled_at";
		assertThatThrownBy(() -> parser.parse(utf8(header + "\n" + VALID_ROW + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsAReorderedHeader() {
		String header = "transaction_id,external_reference,amount,currency,settled_at";
		assertThatThrownBy(() -> parser.parse(utf8(header + "\n" + VALID_ROW + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsARowWithAMissingValue() {
		String row = "EXT-001," + TXN_ID + ",100.00,USD";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsARowWithAnExtraValue() {
		String row = VALID_ROW + ",unexpected";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsMalformedQuoting() {
		// A closing quote immediately followed by more, non-delimiter
		// characters before the next comma is invalid per RFC 4180 --
		// Commons CSV rejects it rather than silently splicing the text.
		String row = "EXT-001," + TXN_ID + ",100.00,\"USD\"stray,2026-07-15T10:00:00Z";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsInvalidUtf8() {
		byte[] header = utf8(HEADER + "\n");
		byte[] invalid = { (byte) 0xFF, (byte) 0xFE, (byte) 0xFD };
		byte[] content = new byte[header.length + invalid.length];
		System.arraycopy(header, 0, content, 0, header.length);
		System.arraycopy(invalid, 0, content, header.length, invalid.length);
		assertThatThrownBy(() -> parser.parse(content, "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void enforcesTheConfiguredRowLimitWhileParsing() {
		SettlementImportProperties properties = new SettlementImportProperties();
		properties.setMaxRowCount(1);
		StringBuilder content = new StringBuilder(HEADER).append('\n');
		content.append("EXT-001,").append(TXN_ID).append(",100.00,USD,2026-07-15T10:00:00Z\n");
		content.append("EXT-002,").append(TXN_ID).append(",100.00,USD,2026-07-15T10:00:00Z\n");
		assertThatThrownBy(() -> parser.parse(utf8(content.toString()), "acme-bank", properties))
				.isInstanceOf(SettlementRowLimitExceededException.class);
	}

	@Test
	void rejectsARepeatedExternalReferenceWithinOneFile() {
		String content = HEADER + "\n" + VALID_ROW + "\nEXT-001," + TXN_ID + ",50.00,USD,2026-07-15T11:00:00Z\n";
		assertThatThrownBy(() -> parser.parse(utf8(content), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	// -- field validation --------------------------------------------------

	@Test
	void rejectsABlankExternalReference() {
		String row = "  ," + TXN_ID + ",100.00,USD,2026-07-15T10:00:00Z";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsAnExternalReferenceExceedingTheConfiguredMaximumLength() {
		SettlementImportProperties properties = new SettlementImportProperties();
		properties.setMaxExternalReferenceLength(4);
		String row = "TOOLONG," + TXN_ID + ",100.00,USD,2026-07-15T10:00:00Z";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", properties))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsAnExternalReferenceContainingControlCharacters() {
		String row = "EXT001," + TXN_ID + ",100.00,USD,2026-07-15T10:00:00Z";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsANonCanonicalTransactionId() {
		String row = "EXT-001,not-a-uuid,100.00,USD,2026-07-15T10:00:00Z";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void parsesAnAmountWithExactlyTwoDecimalPlaces() {
		List<SettlementCsvRow> rows = parser.parse(utf8(HEADER + "\n" + VALID_ROW + "\n"), "acme-bank", defaultProperties());
		assertThat(rows.get(0).amount().scale()).isEqualTo(2);
	}

	@Test
	void rejectsScientificNotationAmounts() {
		String row = "EXT-001," + TXN_ID + ",1e2,USD,2026-07-15T10:00:00Z";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsLocaleFormattedAmountsWithThousandsSeparators() {
		String row = "EXT-001," + TXN_ID + ",\"1,000.00\",USD,2026-07-15T10:00:00Z";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsAmountsWithTheWrongScale() {
		String row = "EXT-001," + TXN_ID + ",100.1,USD,2026-07-15T10:00:00Z";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsAZeroAmount() {
		String row = "EXT-001," + TXN_ID + ",0.00,USD,2026-07-15T10:00:00Z";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsANegativeAmount() {
		String row = "EXT-001," + TXN_ID + ",-100.00,USD,2026-07-15T10:00:00Z";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsALowercaseCurrencyCode() {
		String row = "EXT-001," + TXN_ID + ",100.00,usd,2026-07-15T10:00:00Z";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsAnUnsupportedCurrency() {
		String row = "EXT-001," + TXN_ID + ",100.00,XXX,2026-07-15T10:00:00Z";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void rejectsASettledAtTimestampWithoutAnExplicitOffset() {
		String row = "EXT-001," + TXN_ID + ",100.00,USD,2026-07-15T10:00:00";
		assertThatThrownBy(() -> parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties()))
				.isInstanceOf(InvalidSettlementRequestException.class);
	}

	@Test
	void acceptsASettledAtTimestampWithANonZeroUtcOffset() {
		String row = "EXT-001," + TXN_ID + ",100.00,USD,2026-07-15T10:00:00+02:00";
		List<SettlementCsvRow> rows = parser.parse(utf8(HEADER + "\n" + row + "\n"), "acme-bank", defaultProperties());
		assertThat(rows).hasSize(1);
	}

}
