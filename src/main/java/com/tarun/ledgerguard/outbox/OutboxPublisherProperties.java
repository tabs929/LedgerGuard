package com.tarun.ledgerguard.outbox;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Task 12 publisher configuration, under {@code ledgerguard.outbox.publisher}.
 * Validated at startup ({@code @Validated} + Jakarta Bean Validation) so a
 * misconfigured deployment fails fast and clearly rather than behaving
 * unpredictably at runtime.
 *
 * <p>{@code enabled} defaults to {@code true} for normal application use;
 * every PostgreSQL-only integration test suite that never starts a Kafka
 * broker sets it to {@code false} via {@code application-test.yml}, so
 * those tests never attempt a Kafka connection (see
 * docs/TEST_STRATEGY.md's "Outbox Publisher Tests" section) — only
 * {@code OutboxPublisherIntegrationTest} overrides it back to {@code true}
 * alongside a real Kafka Testcontainer.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "ledgerguard.outbox.publisher")
public class OutboxPublisherProperties {

	private boolean enabled = true;

	@NotBlank
	private String topic = "ledger.transaction-events.v1";

	@Positive
	private int partitions = 3;

	@Positive
	private int replicationFactor = 1;

	@Positive
	private long pollDelayMillis = 2000;

	@Positive
	private int batchSize = 20;

	@Positive
	private long sendTimeoutMillis = 5000;

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getTopic() {
		return topic;
	}

	public void setTopic(String topic) {
		this.topic = topic;
	}

	public int getPartitions() {
		return partitions;
	}

	public void setPartitions(int partitions) {
		this.partitions = partitions;
	}

	public int getReplicationFactor() {
		return replicationFactor;
	}

	public void setReplicationFactor(int replicationFactor) {
		this.replicationFactor = replicationFactor;
	}

	public long getPollDelayMillis() {
		return pollDelayMillis;
	}

	public void setPollDelayMillis(long pollDelayMillis) {
		this.pollDelayMillis = pollDelayMillis;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	public long getSendTimeoutMillis() {
		return sendTimeoutMillis;
	}

	public void setSendTimeoutMillis(long sendTimeoutMillis) {
		this.sendTimeoutMillis = sendTimeoutMillis;
	}

}
