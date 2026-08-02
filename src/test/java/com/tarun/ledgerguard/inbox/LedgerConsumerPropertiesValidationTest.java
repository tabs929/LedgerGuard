package com.tarun.ledgerguard.inbox;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused unit coverage (Task 13) for {@link LedgerConsumerProperties}'
 * Jakarta Bean Validation constraints — no Spring context needed;
 * {@code LedgerGuardApplicationTests} already proves the default (valid)
 * configuration lets the full application context start.
 */
class LedgerConsumerPropertiesValidationTest {

	private static final Validator VALIDATOR;

	static {
		ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
		VALIDATOR = factory.getValidator();
	}

	@Test
	void defaultConfigurationIsValid() {
		assertThat(VALIDATOR.validate(new LedgerConsumerProperties())).isEmpty();
	}

	@Test
	void blankTopicIsRejected() {
		LedgerConsumerProperties properties = new LedgerConsumerProperties();
		properties.setTopic(" ");
		assertHasViolationFor(properties, "topic");
	}

	@Test
	void blankGroupIdIsRejected() {
		LedgerConsumerProperties properties = new LedgerConsumerProperties();
		properties.setGroupId(" ");
		assertHasViolationFor(properties, "groupId");
	}

	@Test
	void nonPositiveConcurrencyIsRejected() {
		LedgerConsumerProperties properties = new LedgerConsumerProperties();
		properties.setConcurrency(0);
		assertHasViolationFor(properties, "concurrency");
	}

	@Test
	void blankAutoOffsetResetIsRejected() {
		LedgerConsumerProperties properties = new LedgerConsumerProperties();
		properties.setAutoOffsetReset(" ");
		assertHasViolationFor(properties, "autoOffsetReset");
	}

	@Test
	void nonPositivePollTimeoutIsRejected() {
		LedgerConsumerProperties properties = new LedgerConsumerProperties();
		properties.setPollTimeoutMillis(-1);
		assertHasViolationFor(properties, "pollTimeoutMillis");
	}

	private void assertHasViolationFor(LedgerConsumerProperties properties, String propertyName) {
		Set<ConstraintViolation<LedgerConsumerProperties>> violations = VALIDATOR.validate(properties);
		assertThat(violations).isNotEmpty();
		assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals(propertyName));
	}

}
