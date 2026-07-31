package com.tarun.ledgerguard.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * Maps exactly to the {@code outbox_event} table created by
 * V3__add_transactional_outbox.sql. One row per completed deposit or
 * transfer ledger transaction, written exactly once by
 * {@link OutboxEventFactory} as part of the same {@code @Transactional}
 * deposit/transfer method that produced the referenced
 * {@code ledger_transaction} row — never independently of it. Immutable
 * once written except for the one future transition
 * {@code published_at NULL -> non-null}, enforced by the database
 * triggers in the V3 migration; this entity itself exposes no setters, so
 * there is no application code path that could even attempt an edit.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEvent {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "aggregate_type", nullable = false, length = 30, updatable = false)
	private OutboxAggregateType aggregateType;

	@Column(name = "aggregate_id", nullable = false, updatable = false)
	private UUID aggregateId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false, length = 30, updatable = false)
	private OutboxEventType eventType;

	@Column(name = "schema_version", nullable = false, updatable = false)
	private int schemaVersion;

	// Pre-serialized JSON text (see OutboxEventFactory) stored as native
	// PostgreSQL jsonb -- Hibernate's built-in SqlTypes.JSON JDBC type,
	// no additional dependency required.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false, updatable = false)
	private String payload;

	@Column(name = "occurred_at", nullable = false, updatable = false)
	private Instant occurredAt;

	@Generated(event = EventType.INSERT)
	@Column(name = "created_at", nullable = false, updatable = false, insertable = false)
	private Instant createdAt;

	@Column(name = "published_at", updatable = false)
	private Instant publishedAt;

	protected OutboxEvent() {
		// required by JPA
	}

	public OutboxEvent(UUID id, OutboxAggregateType aggregateType, UUID aggregateId, OutboxEventType eventType,
			int schemaVersion, String payload, Instant occurredAt) {
		this.id = id;
		this.aggregateType = aggregateType;
		this.aggregateId = aggregateId;
		this.eventType = eventType;
		this.schemaVersion = schemaVersion;
		this.payload = payload;
		this.occurredAt = occurredAt;
	}

	public UUID getId() {
		return id;
	}

	public OutboxAggregateType getAggregateType() {
		return aggregateType;
	}

	public UUID getAggregateId() {
		return aggregateId;
	}

	public OutboxEventType getEventType() {
		return eventType;
	}

	public int getSchemaVersion() {
		return schemaVersion;
	}

	public String getPayload() {
		return payload;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

}
