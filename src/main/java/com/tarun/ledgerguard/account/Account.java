package com.tarun.ledgerguard.account;

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
 * Maps exactly to the existing {@code account} table created by
 * V1__init_account_ledger_schema.sql. This entity does not drive schema
 * generation (spring.jpa.hibernate.ddl-auto is "none" — the Flyway
 * migration is the schema authority) and adds no columns, tables or
 * constraints beyond what that migration already defines.
 */
@Entity
@Table(name = "account")
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Enumerated(EnumType.STRING)
	@Column(name = "account_category", nullable = false, length = 20, updatable = false)
	private AccountCategory accountCategory;

	@Enumerated(EnumType.STRING)
	@Column(name = "account_class", nullable = false, length = 20, updatable = false)
	private AccountClass accountClass;

	@Enumerated(EnumType.STRING)
	@Column(name = "account_purpose", nullable = false, length = 30, updatable = false)
	private AccountPurpose accountPurpose;

	@Column(name = "owner_name", nullable = false, length = 255)
	private String ownerName;

	@Column(name = "currency", nullable = false, length = 3, updatable = false)
	private String currency;

	@Column(name = "balance", nullable = false, precision = 19, scale = 4)
	private BigDecimal balance;

	// Populated by the database's `created_at TIMESTAMPTZ NOT NULL DEFAULT now()`
	// default, not by application code. Excluded from the INSERT statement
	// (insertable = false) and re-read from the database immediately after
	// insert (@Generated) so the in-memory entity reflects the true DB value.
	@Generated(event = EventType.INSERT)
	@Column(name = "created_at", nullable = false, updatable = false, insertable = false)
	private Instant createdAt;

	protected Account() {
		// required by JPA
	}

	public Account(AccountCategory accountCategory, AccountClass accountClass, AccountPurpose accountPurpose,
			String ownerName, String currency, BigDecimal balance) {
		this.accountCategory = accountCategory;
		this.accountClass = accountClass;
		this.accountPurpose = accountPurpose;
		this.ownerName = ownerName;
		this.currency = currency;
		this.balance = balance;
	}

	public UUID getId() {
		return id;
	}

	public AccountCategory getAccountCategory() {
		return accountCategory;
	}

	public AccountClass getAccountClass() {
		return accountClass;
	}

	public AccountPurpose getAccountPurpose() {
		return accountPurpose;
	}

	public String getOwnerName() {
		return ownerName;
	}

	public String getCurrency() {
		return currency;
	}

	public BigDecimal getBalance() {
		return balance;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	/**
	 * Increases the materialized balance in place. The caller is
	 * responsible for having locked this row (see
	 * {@link AccountRepository#findByIdAndFundingAccountForUpdate}) and for
	 * writing the matching balanced ledger entries in the same database
	 * transaction — this method only ever changes the number, never the
	 * ledger.
	 */
	public void increaseBalance(BigDecimal amount) {
		this.balance = this.balance.add(amount);
	}

}
