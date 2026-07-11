package com.anvil.job.repository;

import com.anvil.job.domain.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID> {

    Page<Job> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @Query("SELECT j FROM Job j WHERE (:status IS NULL OR j.status = :status) " +
           "AND (:jobType IS NULL OR j.jobType = :jobType) " +
           "AND (:userId IS NULL OR j.userId = :userId) " +
           "ORDER BY j.createdAt DESC")
    Page<Job> findAllFiltered(@Param("status") String status,
                              @Param("jobType") String jobType,
                              @Param("userId") UUID userId,
                              Pageable pageable);

    @Query("SELECT j FROM Job j WHERE j.status = 'RUNNING' AND j.timeoutAt IS NOT NULL AND j.timeoutAt < :now")
    List<Job> findTimedOutJobs(@Param("now") Instant now);

    @Query("SELECT j FROM Job j WHERE j.status = 'RETRYING' AND j.nextRetryAt IS NOT NULL AND j.nextRetryAt <= :now")
    List<Job> findRetryingJobsDue(@Param("now") Instant now);
}
