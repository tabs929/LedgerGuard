package com.tarun.ledgerguard.inbox;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Map;

/**
 * A dedicated consumer factory and listener container factory for
 * {@link LedgerEventConsumer}, built explicitly from
 * {@link LedgerConsumerProperties} rather than the autoconfigured
 * default container factory — this is what lets
 * {@code auto-offset-reset} and the manual acknowledgement mode live
 * under this task's own validated {@code ledgerguard.inbox.consumer.*}
 * properties instead of only the global {@code spring.kafka.*} ones.
 * Consumer-side {@code bootstrap-servers} and String key/value
 * deserializers still come from {@link KafkaProperties} (the same
 * {@code spring.kafka.*} configuration the Task 12 producer already
 * uses), so both share one source of truth for broker connectivity. Where
 * a {@link KafkaConnectionDetails} bean exists (Testcontainers'
 * {@code @ServiceConnection}, in tests), its bootstrap-servers override
 * is applied explicitly here too — {@link KafkaProperties#buildConsumerProperties()}
 * alone does not know about connection details, since that merging
 * normally happens inside Spring Boot's own {@code KafkaAutoConfiguration}
 * for its default consumer factory, which this class deliberately does
 * not use.
 *
 * <p>{@code enable.auto.commit} is forced to {@code false} and the
 * container's acknowledgement mode to
 * {@link ContainerProperties.AckMode#MANUAL_IMMEDIATE} — see
 * {@link LedgerEventConsumer} for exactly when {@code acknowledge()} is
 * called and why.
 *
 * <p>Conditional on the consumer being enabled: when it is not (every
 * PostgreSQL-only integration test's shared {@code application-test.yml}),
 * neither this factory nor {@link LedgerEventConsumer} (which references
 * it by name) exists, so nothing here ever attempts a Kafka connection.
 */
@Configuration
@ConditionalOnProperty(prefix = "ledgerguard.inbox.consumer", name = "enabled", havingValue = "true")
public class LedgerEventConsumerConfig {

	public static final String CONTAINER_FACTORY_BEAN_NAME = "ledgerEventListenerContainerFactory";

	@Bean
	public ConsumerFactory<String, String> ledgerEventConsumerFactory(KafkaProperties kafkaProperties,
			LedgerConsumerProperties properties, ObjectProvider<KafkaConnectionDetails> connectionDetailsProvider) {
		Map<String, Object> configs = kafkaProperties.buildConsumerProperties();
		KafkaConnectionDetails connectionDetails = connectionDetailsProvider.getIfAvailable();
		if (connectionDetails != null) {
			configs.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getConsumer().getBootstrapServers());
		}
		configs.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, properties.getAutoOffsetReset());
		configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		return new DefaultKafkaConsumerFactory<>(configs);
	}

	@Bean(CONTAINER_FACTORY_BEAN_NAME)
	public ConcurrentKafkaListenerContainerFactory<String, String> ledgerEventListenerContainerFactory(
			ConsumerFactory<String, String> ledgerEventConsumerFactory, LedgerConsumerProperties properties) {
		ConcurrentKafkaListenerContainerFactory<String, String> factory = new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(ledgerEventConsumerFactory);
		factory.setConcurrency(properties.getConcurrency());
		ContainerProperties containerProperties = factory.getContainerProperties();
		containerProperties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
		containerProperties.setPollTimeout(properties.getPollTimeoutMillis());
		return factory;
	}

}
