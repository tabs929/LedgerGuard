package com.tarun.ledgerguard.outbox;

import java.util.UUID;

/**
 * Wraps a Kafka send/acknowledgement failure for one outbox event as an
 * unchecked exception, so it propagates out of
 * {@code OutboxPublisher.publishIfPending}'s {@code @Transactional}
 * method and triggers a normal Spring rollback — leaving
 * {@code published_at} untouched (still {@code NULL}) and the row
 * available for a later polling attempt. Never thrown for any reason
 * other than a real send/acknowledgement failure; never used to suppress
 * or hide an error.
 */
public class OutboxPublishException extends RuntimeException {

	public OutboxPublishException(UUID eventId, Throwable cause) {
		super("Failed to publish outbox event " + eventId, cause);
	}

}
