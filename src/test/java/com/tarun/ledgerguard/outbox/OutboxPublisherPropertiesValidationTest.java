package com.tarun.ledgerguard.outbox;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused unit coverage (Task 12) for {@link OutboxPublisherProperties}'
 * Jakarta Bean Validation constraints — no Spring context needed to prove
 * these fire; {@code LedgerGuardApplicationTests} already proves the
 * default (valid) configuration lets the full application context start.
 */
class OutboxPublisherPropertiesValidationTest {

	private static final Validator VALIDATOR;

	static {
		ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
		VALIDATOR = factory.getValidator();
	}

	@Test
	void defaultConfigurationIsValid() {
		assertThat(VALIDATOR.validate(new OutboxPublisherProperties())).isEmpty();
	}

	@Test
	void blankTopicIsRejected() {
		OutboxPublisherProperties properties = new OutboxPublisherProperties();
		properties.setTopic("  ");
		assertHasViolationFor(properties, "topic");
	}

	@Test
	void nonPositivePartitionsIsRejected() {
		OutboxPublisherProperties properties = new OutboxPublisherProperties();
		properties.setPartitions(0);
		assertHasViolationFor(properties, "partitions");
	}

	@Test
	void nonPositiveReplicationFactorIsRejected() {
		OutboxPublisherProperties properties = new OutboxPublisherProperties();
		properties.setReplicationFactor(-1);
		assertHasViolationFor(properties, "replicationFactor");
	}

	@Test
	void nonPositivePollDelayIsRejected() {
		OutboxPublisherProperties properties = new OutboxPublisherProperties();
		properties.setPollDelayMillis(0);
		assertHasViolationFor(properties, "pollDelayMillis");
	}

	@Test
	void nonPositiveBatchSizeIsRejected() {
		OutboxPublisherProperties properties = new OutboxPublisherProperties();
		properties.setBatchSize(0);
		assertHasViolationFor(properties, "batchSize");
	}

	@Test
	void nonPositiveSendTimeoutIsRejected() {
		OutboxPublisherProperties properties = new OutboxPublisherProperties();
		properties.setSendTimeoutMillis(-5);
		assertHasViolationFor(properties, "sendTimeoutMillis");
	}

	private void assertHasViolationFor(OutboxPublisherProperties properties, String propertyName) {
		Set<ConstraintViolation<OutboxPublisherProperties>> violations = VALIDATOR.validate(properties);
		assertThat(violations).isNotEmpty();
		assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals(propertyName));
	}

}
