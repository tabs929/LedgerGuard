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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps exactly to the existing {@code ledger_entry} table created by
 * V1__init_account_ledger_schema.sql. References to the owning transaction
 * and account are plain UUID foreign-key columns rather than JPA
 * {@code @ManyToOne} associations — deposit processing only ever needs to
 * write these ids, never navigate an object graph through them, so an
 * association mapping would be unused machinery. Immutable once written —
 * the database rejects UPDATE/DELETE via trg_ledger_entry_immutable; this
 * entity exposes no setters and no update path.
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "transaction_id", nullable = false, updatable = false)
	private UUID transactionId;

	@Column(name = "account_id", nullable = false, updatable = false)
	private UUID accountId;

	@Enumerated(EnumType.STRING)
	@Column(name = "entry_type", nullable = false, length = 10, updatable = false)
	private LedgerEntryType entryType;

	@Column(name = "amount", nullable = false, precision = 19, scale = 4, updatable = false)
	private BigDecimal amount;

	@Column(name = "currency", nullable = false, length = 3, updatable = false)
	private String currency;

	@Generated(event = EventType.INSERT)
	@Column(name = "created_at", nullable = false, updatable = false, insertable = false)
	private Instant createdAt;

	protected LedgerEntry() {
		// required by JPA
	}

	public LedgerEntry(UUID transactionId, UUID accountId, LedgerEntryType entryType, BigDecimal amount,
			String currency) {
		this.transactionId = transactionId;
		this.accountId = accountId;
		this.entryType = entryType;
		this.amount = amount;
		this.currency = currency;
	}

	public UUID getId() {
		return id;
	}

	public UUID getTransactionId() {
		return transactionId;
	}

	public UUID getAccountId() {
		return accountId;
	}

	public LedgerEntryType getEntryType() {
		return entryType;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public String getCurrency() {
		return currency;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
