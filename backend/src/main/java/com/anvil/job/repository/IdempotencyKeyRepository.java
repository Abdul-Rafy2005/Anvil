package com.anvil.job.repository;

import com.anvil.job.domain.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, UUID> {
    Optional<IdempotencyKey> findByIdempotencyKeyAndUserId(String idempotencyKey, UUID userId);
}
