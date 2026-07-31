package com.tarun.ledgerguard.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps exactly to the {@code idempotency_key} table created by
 * V2__add_idempotency_key.sql. One row per Idempotency-Key value ever
 * successfully claimed by a deposit or transfer; retained indefinitely
 * (see docs/DATA_MODEL.md — no TTL/cleanup in Phase 2). Written exactly
 * once, as the last statement of the same {@code @Transactional} method
 * that performed the underlying deposit/transfer — never updated
 * afterward, so this entity exposes no setters.
 */
@Entity
@Table(name = "idempotency_key")
public class IdempotencyKeyRecord {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
	private String idempotencyKey;

	@Enumerated(EnumType.STRING)
	@Column(name = "operation_type", nullable = false, length = 20, updatable = false)
	private IdempotencyOperationType operationType;

	@Column(name = "primary_account_id", nullable = false, updatable = false)
	private UUID primaryAccountId;

	@Column(name = "secondary_account_id", updatable = false)
	private UUID secondaryAccountId;

	@Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
	private BigDecimal amount;

	@Column(name = "currency", nullable = false, length = 3, updatable = false)
	private String currency;

	@Column(name = "command_hash", nullable = false, length = 64, updatable = false)
	private String commandHash;

	@Column(name = "ledger_transaction_id", nullable = false, updatable = false)
	private UUID ledgerTransactionId;

	@Column(name = "response_status", nullable = false, updatable = false)
	private int responseStatus;

	@Column(name = "response_body", nullable = false, updatable = false)
	private String responseBody;

	@Generated(event = EventType.INSERT)
	@Column(name = "created_at", nullable = false, updatable = false, insertable = false)
	private Instant createdAt;

	protected IdempotencyKeyRecord() {
		// required by JPA
	}

	public IdempotencyKeyRecord(String idempotencyKey, IdempotencyCommand command, UUID ledgerTransactionId,
			int responseStatus, String responseBody) {
		this.idempotencyKey = idempotencyKey;
		this.operationType = command.operationType();
		this.primaryAccountId = command.primaryAccountId();
		this.secondaryAccountId = command.secondaryAccountId();
		this.amount = command.amount();
		this.currency = command.currency();
		this.commandHash = command.sha256Hex();
		this.ledgerTransactionId = ledgerTransactionId;
		this.responseStatus = responseStatus;
		this.responseBody = responseBody;
	}

	public boolean matches(IdempotencyCommand command) {
		return toCommand().matches(command);
	}

	private IdempotencyCommand toCommand() {
		return new IdempotencyCommand(operationType, primaryAccountId, secondaryAccountId, amount, currency);
	}

	public UUID getId() {
		return id;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public IdempotencyOperationType getOperationType() {
		return operationType;
	}

	public UUID getPrimaryAccountId() {
		return primaryAccountId;
	}

	public UUID getSecondaryAccountId() {
		return secondaryAccountId;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public String getCommandHash() {
		return commandHash;
	}

	public UUID getLedgerTransactionId() {
		return ledgerTransactionId;
	}

	public int getResponseStatus() {
		return responseStatus;
	}

	public String getResponseBody() {
		return responseBody;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
