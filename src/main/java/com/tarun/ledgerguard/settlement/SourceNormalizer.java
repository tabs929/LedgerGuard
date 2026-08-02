package com.tarun.ledgerguard.settlement;

import java.util.Locale;

/**
 * Derives the lowercase identity value ({@code normalized_source}) used
 * for both settlement identities -- (normalized_source, file_hash) on
 * {@code settlement_import} and (normalized_source, external_reference) on
 * {@code settlement_record} -- from a caller-supplied {@code source}
 * value. The caller is responsible for trimming and rejecting a blank
 * value first ({@code SettlementImportService}); this class only applies
 * the documented case-folding policy: preserve the submitted display
 * value as-is (stored separately in {@code settlement_import.source}),
 * derive a lowercase value for identity comparison.
 */
final class SourceNormalizer {

	private SourceNormalizer() {
	}

	static String normalize(String trimmedSource) {
		return trimmedSource.toLowerCase(Locale.ROOT);
	}

}
