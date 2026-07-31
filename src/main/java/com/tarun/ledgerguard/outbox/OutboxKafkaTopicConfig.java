package com.tarun.ledgerguard.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Declares the one Kafka topic Task 12 publishes to, via Spring Kafka's
 * {@link org.springframework.kafka.core.KafkaAdmin}-backed topic
 * management (a {@link NewTopic} bean) rather than relying on broker
 * auto-creation — see docs/ARCHITECTURE.md's "Kafka Publishing" section
 * for why tests must not depend on uncontrolled auto-creation.
 *
 * <p>Conditional on the publisher being enabled: when it is not (every
 * PostgreSQL-only integration test's shared {@code application-test.yml}),
 * no {@code NewTopic} bean exists, so {@code KafkaAdmin} has nothing to
 * create and never attempts to reach a broker at context startup.
 */
@Configuration
@ConditionalOnProperty(prefix = "ledgerguard.outbox.publisher", name = "enabled", havingValue = "true")
public class OutboxKafkaTopicConfig {

	@Bean
	public NewTopic ledgerTransactionEventsTopic(OutboxPublisherProperties properties) {
		return TopicBuilder.name(properties.getTopic())
				.partitions(properties.getPartitions())
				.replicas(properties.getReplicationFactor())
				.build();
	}

}
