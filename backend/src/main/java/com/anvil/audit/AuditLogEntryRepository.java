package com.anvil.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;
import java.util.UUID;

public interface AuditLogEntryRepository extends JpaRepository<AuditLogEntry, UUID> {

    @Query("SELECT a FROM AuditLogEntry a WHERE " +
           "(:actorUserId IS NULL OR a.actorUserId = :actorUserId) " +
           "AND (:action IS NULL OR a.action = :action) " +
           "AND (:targetId IS NULL OR a.targetId = :targetId) " +
           "AND (:since IS NULL OR a.createdAt >= :since) " +
           "AND (:until IS NULL OR a.createdAt <= :until) " +
           "ORDER BY a.createdAt DESC")
    Page<AuditLogEntry> findAllFiltered(
            @Param("actorUserId") UUID actorUserId,
            @Param("action") String action,
            @Param("targetId") UUID targetId,
            @Param("since") Instant since,
            @Param("until") Instant until,
            Pageable pageable);
}
