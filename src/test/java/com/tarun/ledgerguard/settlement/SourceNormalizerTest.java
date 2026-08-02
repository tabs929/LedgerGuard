package com.tarun.ledgerguard.settlement;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourceNormalizerTest {

	@Test
	void lowercasesTheSource() {
		assertThat(SourceNormalizer.normalize("Acme-Bank")).isEqualTo("acme-bank");
	}

	@Test
	void isIdempotentForAlreadyLowercaseInput() {
		assertThat(SourceNormalizer.normalize("acme-bank")).isEqualTo("acme-bank");
	}

	@Test
	void differentCasingsOfTheSameSourceNormalizeIdentically() {
		assertThat(SourceNormalizer.normalize("ACME-BANK")).isEqualTo(SourceNormalizer.normalize("acme-bank"));
	}

}
