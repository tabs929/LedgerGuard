package com.tarun.ledgerguard.inbox;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Task 13 consumer configuration, under {@code ledgerguard.inbox.consumer}.
 * Validated at startup, the same pattern
 * {@code outbox.OutboxPublisherProperties} (Task 12) already established.
 *
 * <p>{@code enabled} defaults to {@code true} for normal application use;
 * every PostgreSQL-only integration test suite that never starts a Kafka
 * broker sets both this and {@code ledgerguard.outbox.publisher.enabled}
 * to {@code false} via {@code application-test.yml}, so those tests never
 * attempt a Kafka connection — only
 * {@code LedgerEventConsumerIntegrationTest} overrides it back to
 * {@code true} alongside a real Kafka Testcontainer.
 */
@Component
@Validated
@ConfigurationProperties(prefix = "ledgerguard.inbox.consumer")
public class LedgerConsumerProperties {

	private boolean enabled = true;

	@NotBlank
	private String topic = "ledger.transaction-events.v1";

	@NotBlank
	private String groupId = "ledgerguard-transaction-event-consumer-v1";

	@Positive
	private int concurrency = 3;

	@NotBlank
	private String autoOffsetReset = "earliest";

	@Positive
	private long pollTimeoutMillis = 5000;

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

	public String getGroupId() {
		return groupId;
	}

	public void setGroupId(String groupId) {
		this.groupId = groupId;
	}

	public int getConcurrency() {
		return concurrency;
	}

	public void setConcurrency(int concurrency) {
		this.concurrency = concurrency;
	}

	public String getAutoOffsetReset() {
		return autoOffsetReset;
	}

	public void setAutoOffsetReset(String autoOffsetReset) {
		this.autoOffsetReset = autoOffsetReset;
	}

	public long getPollTimeoutMillis() {
		return pollTimeoutMillis;
	}

	public void setPollTimeoutMillis(long pollTimeoutMillis) {
		this.pollTimeoutMillis = pollTimeoutMillis;
	}

}
