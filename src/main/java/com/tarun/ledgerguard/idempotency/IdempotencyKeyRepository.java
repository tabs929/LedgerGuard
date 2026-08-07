package com.tarun.ledgerguard.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyRecord, UUID> {

	Optional<IdempotencyKeyRecord> findByPrincipalSubjectAndIdempotencyKey(String principalSubject, String idempotencyKey);

}
