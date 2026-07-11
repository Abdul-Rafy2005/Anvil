package com.anvil.queue;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.UUID;

public interface OutboxEntryRepository extends JpaRepository<OutboxEntry, UUID> {

    @Query("SELECT o FROM OutboxEntry o WHERE o.status = 'PENDING' OR (o.status = 'FAILED' AND o.nextRetryAt IS NOT NULL AND o.nextRetryAt <= CURRENT_TIMESTAMP) ORDER BY o.createdAt ASC")
    List<OutboxEntry> findRetryableEntries();

    boolean existsByJobIdAndStatus(UUID jobId, OutboxStatus status);

    @Modifying
    @Transactional
    @Query("DELETE FROM OutboxEntry o WHERE o.jobId = :jobId")
    void deleteByJobId(UUID jobId);
}
