package com.tarun.ledgerguard.account;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

	/**
	 * Locks the destination customer account and the unique SYSTEM
	 * EXTERNAL_FUNDING account for the given currency in a single query,
	 * in ascending account-id order (via {@code ORDER BY a.id} combined
	 * with {@code PESSIMISTIC_WRITE}) — never by role. Per
	 * docs/ARCHITECTURE.md, this deterministic ordering (not
	 * customer-then-funding) is what prevents deadlocks between concurrent
	 * operations that would otherwise lock the same two kinds of row in
	 * different orders, and prevents a lost update on the shared funding
	 * row when concurrent deposits target different customers.
	 *
	 * <p>The funding account is not looked up by an id supplied by the
	 * caller (there is no such id in the request) — it is resolved purely
	 * from its protected taxonomy (SYSTEM + ASSET + EXTERNAL_FUNDING) and
	 * currency, so a client can never choose or override which account is
	 * debited.
	 *
	 * <p>Returns 0, 1 or 2 rows depending on which accounts exist and
	 * whether the destination id happens to already be the funding account
	 * itself (in which case only one distinct row matches both conditions).
	 * Callers must not assume exactly two rows are returned.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select a from Account a
			where a.id = :destinationAccountId
			   or (a.accountCategory = com.tarun.ledgerguard.account.AccountCategory.SYSTEM
			       and a.accountPurpose = com.tarun.ledgerguard.account.AccountPurpose.EXTERNAL_FUNDING
			       and a.currency = :currency)
			order by a.id
			""")
	List<Account> findByIdAndFundingAccountForUpdate(
			@Param("destinationAccountId") UUID destinationAccountId,
			@Param("currency") String currency);

}
