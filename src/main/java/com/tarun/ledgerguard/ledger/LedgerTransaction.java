package com.tarun.ledgerguard.ledger;

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

import java.time.Instant;
import java.util.UUID;

/**
 * Maps exactly to the existing {@code ledger_transaction} table created by
 * V1__init_account_ledger_schema.sql (the transaction header row grouping
 * a set of {@link LedgerEntry} rows). Immutable once written — the database
 * rejects UPDATE/DELETE via trg_ledger_transaction_immutable; this entity
 * exposes no setters and no update path.
 */
@Entity
@Table(name = "ledger_transaction")
public class LedgerTransaction {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "transaction_type", nullable = false, length = 30, updatable = false)
	private TransactionType transactionType;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20, updatable = false)
	private TransactionStatus status;

	@Generated(event = EventType.INSERT)
	@Column(name = "created_at", nullable = false, updatable = false, insertable = false)
	private Instant createdAt;

	protected LedgerTransaction() {
		// required by JPA
	}

	public LedgerTransaction(TransactionType transactionType, TransactionStatus status) {
		this.transactionType = transactionType;
		this.status = status;
	}

	public UUID getId() {
		return id;
	}

	public TransactionType getTransactionType() {
		return transactionType;
	}

	public TransactionStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
