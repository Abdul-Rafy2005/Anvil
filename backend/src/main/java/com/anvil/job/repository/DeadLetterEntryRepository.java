package com.anvil.job.repository;

import com.anvil.job.domain.DeadLetterEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DeadLetterEntryRepository extends JpaRepository<DeadLetterEntry, UUID> {

    Optional<DeadLetterEntry> findByJobId(UUID jobId);

    boolean existsByJobId(UUID jobId);

    @Query("SELECT d FROM DeadLetterEntry d WHERE " +
           "(:jobType IS NULL OR d.jobType = :jobType) " +
           "AND (:resolved IS NULL OR (CASE WHEN d.resolvedAction IS NULL THEN false ELSE true END) = :resolved) " +
           "ORDER BY d.createdAt DESC")
    Page<DeadLetterEntry> findAllFiltered(@Param("jobType") String jobType,
                                          @Param("resolved") Boolean resolved,
                                          Pageable pageable);
}
