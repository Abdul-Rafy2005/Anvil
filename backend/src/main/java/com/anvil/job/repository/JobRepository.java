package com.anvil.job.repository;

import com.anvil.job.domain.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID>, JpaSpecificationExecutor<Job> {

    Page<Job> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    @Query("SELECT j FROM Job j WHERE j.status = 'RUNNING' AND j.timeoutAt IS NOT NULL AND j.timeoutAt < :now")
    List<Job> findTimedOutJobs(@Param("now") Instant now);

    @Query("SELECT j FROM Job j WHERE j.status = 'RETRYING' AND j.nextRetryAt IS NOT NULL AND j.nextRetryAt <= :now")
    List<Job> findRetryingJobsDue(@Param("now") Instant now);

    @Query(value = "SELECT * FROM jobs WHERE status = 'CREATED' AND scheduled_at IS NOT NULL AND scheduled_at <= :now LIMIT :limit FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<Job> findDueScheduledJobs(@Param("now") Instant now, @Param("limit") int limit);

    @Query(value = "SELECT * FROM jobs WHERE status = 'CREATED' AND cron_expression IS NOT NULL AND next_fire_at IS NOT NULL AND next_fire_at <= :now LIMIT :limit FOR UPDATE SKIP LOCKED",
           nativeQuery = true)
    List<Job> findDueCronJobs(@Param("now") Instant now, @Param("limit") int limit);

    long countByStatus(com.anvil.job.domain.JobStatus status);

    List<Job> findByStatus(com.anvil.job.domain.JobStatus status);

    long countByStatusIn(List<com.anvil.job.domain.JobStatus> statuses);

    @Query("SELECT COUNT(j) FROM Job j WHERE j.status IN :statuses AND j.completedAt >= :since")
    long countByStatusInAndCompletedAtSince(@Param("statuses") List<com.anvil.job.domain.JobStatus> statuses,
                                            @Param("since") Instant since);

    @Query(value = "SELECT job_type, AVG(EXTRACT(EPOCH FROM (completed_at - started_at))) " +
           "FROM jobs WHERE status = 'COMPLETED' AND started_at IS NOT NULL AND completed_at IS NOT NULL " +
           "GROUP BY job_type", nativeQuery = true)
    List<Object[]> averageProcessingTimePerJobType();

    @Query(value = "SELECT AVG(EXTRACT(EPOCH FROM (completed_at - started_at))) " +
           "FROM jobs WHERE status = 'COMPLETED' AND started_at IS NOT NULL AND completed_at IS NOT NULL",
           nativeQuery = true)
    Double averageProcessingTimeOverall();

    @Query("SELECT COUNT(j) FROM Job j WHERE j.priority = :priority AND j.status IN ('QUEUED', 'CREATED')")
    long countWaitingByPriority(@org.springframework.data.repository.query.Param("priority") com.anvil.job.domain.JobPriority priority);
}
