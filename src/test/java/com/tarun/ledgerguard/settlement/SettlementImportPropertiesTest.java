package com.tarun.ledgerguard.settlement;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the Jakarta Bean Validation constraints directly (no Spring
 * context needed) -- the same constraints
 * {@code @Validated}/{@code @ConfigurationProperties} enforce at
 * application startup.
 */
class SettlementImportPropertiesTest {

	private static ValidatorFactory validatorFactory;
	private static Validator validator;

	@BeforeAll
	static void setUpValidator() {
		validatorFactory = Validation.buildDefaultValidatorFactory();
		validator = validatorFactory.getValidator();
	}

	@AfterAll
	static void closeValidator() {
		validatorFactory.close();
	}

	@Test
	void defaultsAreValid() {
		SettlementImportProperties properties = new SettlementImportProperties();
		assertThat(validator.validate(properties)).isEmpty();
	}

	@Test
	void rejectsNonPositiveMaxFileSizeBytes() {
		SettlementImportProperties properties = new SettlementImportProperties();
		properties.setMaxFileSizeBytes(0);
		Set<ConstraintViolation<SettlementImportProperties>> violations = validator.validate(properties);
		assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("maxFileSizeBytes"));
	}

	@Test
	void rejectsNonPositiveMaxRowCount() {
		SettlementImportProperties properties = new SettlementImportProperties();
		properties.setMaxRowCount(-1);
		Set<ConstraintViolation<SettlementImportProperties>> violations = validator.validate(properties);
		assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("maxRowCount"));
	}

	@Test
	void rejectsNonPositiveMaxSourceLength() {
		SettlementImportProperties properties = new SettlementImportProperties();
		properties.setMaxSourceLength(0);
		Set<ConstraintViolation<SettlementImportProperties>> violations = validator.validate(properties);
		assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("maxSourceLength"));
	}

	@Test
	void rejectsNonPositiveMaxExternalReferenceLength() {
		SettlementImportProperties properties = new SettlementImportProperties();
		properties.setMaxExternalReferenceLength(0);
		Set<ConstraintViolation<SettlementImportProperties>> violations = validator.validate(properties);
		assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("maxExternalReferenceLength"));
	}

	@Test
	void disabledIsAValidConfiguration() {
		SettlementImportProperties properties = new SettlementImportProperties();
		properties.setEnabled(false);
		assertThat(validator.validate(properties)).isEmpty();
	}

}
