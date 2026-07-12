package com.anvil.job.service;

import com.anvil.api.dto.CreateJobRequest;
import com.anvil.api.dto.JobResponse;
import com.anvil.api.dto.JobListResponse;
import com.anvil.config.MetricsService;
import com.anvil.job.domain.*;
import com.anvil.job.repository.IdempotencyKeyRepository;
import com.anvil.job.repository.JobRepository;
import com.anvil.queue.OutboxEntry;
import com.anvil.queue.OutboxEntryRepository;
import com.anvil.scheduler.CronSchedulerHelper;
import jakarta.persistence.criteria.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OutboxEntryRepository outboxRepository;
    private final JobStateMachine stateMachine;
    private final CronSchedulerHelper cronHelper;
    private final MetricsService metricsService;

    public JobService(JobRepository jobRepository,
                      IdempotencyKeyRepository idempotencyKeyRepository,
                      OutboxEntryRepository outboxRepository,
                      JobStateMachine stateMachine,
                      CronSchedulerHelper cronHelper,
                      MetricsService metricsService) {
        this.jobRepository = jobRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.outboxRepository = outboxRepository;
        this.stateMachine = stateMachine;
        this.cronHelper = cronHelper;
        this.metricsService = metricsService;
    }

    @Transactional
    public JobResponse createJob(CreateJobRequest request, UUID userId, String idempotencyKey) {
        long start = System.nanoTime();

        if (idempotencyKey != null) {
            var existing = idempotencyKeyRepository
                    .findByIdempotencyKeyAndUserId(idempotencyKey, userId);
            if (existing.isPresent()) {
                Job existingJob = jobRepository.findById(existing.get().getJobId()).orElseThrow();
                log.info("Idempotent hit: key={} returning existing job={}", idempotencyKey, existingJob.getId());
                metricsService.recordSubmissionTime(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                return toResponse(existingJob);
            }
        }

        boolean hasScheduledAt = request.scheduledAt() != null;
        boolean hasCron = request.cronExpression() != null && !request.cronExpression().isBlank();

        if (hasScheduledAt && hasCron) {
            throw new IllegalArgumentException("scheduledAt and cronExpression are mutually exclusive");
        }

        Job job = new Job(userId, request.jobType(), request.payload(), JobStatus.CREATED);
        if (request.priority() != null) {
            job.setPriority(request.priority());
        }

        if (hasScheduledAt) {
            if (request.scheduledAt().isBefore(Instant.now())) {
                throw new IllegalArgumentException("scheduledAt must be in the future");
            }
            job.setScheduledAt(request.scheduledAt());
            job.setNextFireAt(request.scheduledAt());
            job = jobRepository.save(job);
            log.info("Job scheduled: id={} type={} user={} fireAt={}", job.getId(), job.getJobType(), userId, request.scheduledAt());
        } else if (hasCron) {
            cronHelper.validate(request.cronExpression());
            job.setCronExpression(request.cronExpression());
            Instant firstFireAt = cronHelper.getNextFireTime(request.cronExpression(), Instant.now());
            job.setNextFireAt(firstFireAt);
            job = jobRepository.save(job);
            log.info("Cron job created: id={} type={} user={} cron={} nextFire={}", job.getId(), job.getJobType(), userId, request.cronExpression(), firstFireAt);
        } else {
            job = jobRepository.save(job);
            stateMachine.transition(job, JobStatus.QUEUED, userId, "Job created");
            job = jobRepository.save(job);
            outboxRepository.save(new OutboxEntry(job.getId(), job.getPriority()));
            log.info("Job created: id={} type={} user={}", job.getId(), job.getJobType(), userId);
        }

        if (idempotencyKey != null) {
            idempotencyKeyRepository.save(new IdempotencyKey(idempotencyKey, userId, job.getId()));
        }

        metricsService.recordJobSubmitted();
        metricsService.recordSubmissionTime(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public Page<JobListResponse> listJobs(UUID userId, boolean isAdmin,
                                           String status, String jobType,
                                           Pageable pageable) {
        Specification<Job> spec = (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null && !status.isBlank()) {
                predicates.add(cb.equal(root.get("status"), JobStatus.valueOf(status)));
            }
            if (jobType != null && !jobType.isBlank()) {
                predicates.add(cb.equal(root.get("jobType"), jobType));
            }
            if (!isAdmin) {
                predicates.add(cb.equal(root.get("userId"), userId));
            }
            return cb.and(predicates.toArray(new Predicate[0]));
        };
        return jobRepository.findAll(spec, pageable).map(JobListResponse::from);
    }

    @Transactional(readOnly = true)
    public JobResponse getJob(UUID jobId, UUID userId, boolean isAdmin) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));

        if (!isAdmin && !job.getUserId().equals(userId)) {
            throw new JobNotFoundException(jobId);
        }

        return toResponse(job);
    }

    @Transactional
    public JobResponse cancelJob(UUID jobId, UUID userId, boolean isAdmin) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new JobNotFoundException(jobId));

        if (!isAdmin && !job.getUserId().equals(userId)) {
            throw new JobNotFoundException(jobId);
        }

        if (!stateMachine.canCancel(job.getStatus())) {
            throw new InvalidJobTransitionException(job.getStatus(), JobStatus.CANCELLED);
        }

        if (job.getStatus() == JobStatus.RUNNING) {
            stateMachine.transition(job, JobStatus.CANCELLING, userId, "User cancelled");
        } else {
            stateMachine.transition(job, JobStatus.CANCELLED, userId, "User cancelled");
        }
        job = jobRepository.save(job);

        log.info("Job cancel requested: id={} by user={} newStatus={}", jobId, userId, job.getStatus());
        return toResponse(job);
    }

    private JobResponse toResponse(Job job) {
        return new JobResponse(
                job.getId(), job.getUserId(), job.getJobType(), job.getPayload(),
                job.getStatus(), job.getPriority(), job.getProgressPct(),
                job.getProgressMessage(), job.getResult(), job.getErrorMessage(),
                job.getAttemptCount(), job.getMaxRetries(),
                job.getScheduledAt(), job.getCronExpression(), job.getNextFireAt(),
                job.getCreatedAt(), job.getUpdatedAt(),
                job.getStartedAt(), job.getCompletedAt());
    }
}
