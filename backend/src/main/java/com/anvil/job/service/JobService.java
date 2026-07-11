package com.anvil.job.service;

import com.anvil.api.dto.CreateJobRequest;
import com.anvil.api.dto.JobResponse;
import com.anvil.api.dto.JobListResponse;
import com.anvil.audit.AuditLogService;
import com.anvil.job.domain.*;
import com.anvil.job.repository.IdempotencyKeyRepository;
import com.anvil.job.repository.JobRepository;
import com.anvil.queue.OutboxEntry;
import com.anvil.queue.OutboxEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final OutboxEntryRepository outboxRepository;
    private final JobStateMachine stateMachine;

    public JobService(JobRepository jobRepository,
                      IdempotencyKeyRepository idempotencyKeyRepository,
                      OutboxEntryRepository outboxRepository,
                      JobStateMachine stateMachine) {
        this.jobRepository = jobRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.outboxRepository = outboxRepository;
        this.stateMachine = stateMachine;
    }

    @Transactional
    public JobResponse createJob(CreateJobRequest request, UUID userId, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = idempotencyKeyRepository
                    .findByIdempotencyKeyAndUserId(idempotencyKey, userId);
            if (existing.isPresent()) {
                Job existingJob = jobRepository.findById(existing.get().getJobId()).orElseThrow();
                log.info("Idempotent hit: key={} returning existing job={}", idempotencyKey, existingJob.getId());
                return toResponse(existingJob);
            }
        }

        Job job = new Job(userId, request.jobType(), request.payload(), JobStatus.CREATED);
        if (request.priority() != null) {
            job.setPriority(request.priority());
        }

        job = jobRepository.save(job);
        stateMachine.transition(job, JobStatus.QUEUED, userId, "Job created");
        job = jobRepository.save(job);

        outboxRepository.save(new OutboxEntry(job.getId(), job.getPriority()));

        if (idempotencyKey != null) {
            idempotencyKeyRepository.save(new IdempotencyKey(idempotencyKey, userId, job.getId()));
        }

        log.info("Job created: id={} type={} user={}", job.getId(), job.getJobType(), userId);
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public Page<JobListResponse> listJobs(UUID userId, boolean isAdmin,
                                           String status, String jobType,
                                           Pageable pageable) {
        UUID filterUserId = isAdmin ? null : userId;
        return jobRepository.findAllFiltered(status, jobType, filterUserId, pageable)
                .map(JobListResponse::from);
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
                job.getCreatedAt(), job.getUpdatedAt(),
                job.getStartedAt(), job.getCompletedAt());
    }
}
