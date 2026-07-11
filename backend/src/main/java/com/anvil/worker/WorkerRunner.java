package com.anvil.worker;

import com.anvil.admin.DeadLetterService;
import com.anvil.job.domain.*;
import com.anvil.job.handler.*;
import com.anvil.job.repository.JobAttemptRepository;
import com.anvil.job.repository.JobRepository;
import com.anvil.queue.AnvilQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class WorkerRunner {

    private static final Logger log = LoggerFactory.getLogger(WorkerRunner.class);

    private final AnvilQueue queue;
    private final JobRepository jobRepository;
    private final JobAttemptRepository attemptRepository;
    private final JobStateMachine stateMachine;
    private final WorkerRepository workerRepository;
    private final JobHandlerRegistry handlerRegistry;
    private final DeadLetterService deadLetterService;
    private final long[] retryBackoffSeconds;

    @Value("${worker.id:default}")
    private String workerId;

    @Value("${worker.visibility-timeout-seconds:30}")
    private int visibilityTimeoutSeconds;

    public WorkerRunner(AnvilQueue queue,
                        JobRepository jobRepository,
                        JobAttemptRepository attemptRepository,
                        JobStateMachine stateMachine,
                        WorkerRepository workerRepository,
                        JobHandlerRegistry handlerRegistry,
                        DeadLetterService deadLetterService,
                        @Value("${worker.retry-backoff-seconds:30,60,120,240}") String retryBackoffCsv) {
        this.queue = queue;
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
        this.stateMachine = stateMachine;
        this.workerRepository = workerRepository;
        this.handlerRegistry = handlerRegistry;
        this.deadLetterService = deadLetterService;
        this.retryBackoffSeconds = parseBackoff(retryBackoffCsv);
    }

    private static long[] parseBackoff(String csv) {
        String[] parts = csv.split(",");
        long[] result = new long[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Long.parseLong(parts[i].trim());
        }
        return result;
    }

    @Scheduled(fixedDelayString = "${worker.poll-interval-ms:2000}",
               initialDelayString = "${worker.poll-interval-ms:2000}")
    public void poll() {
        Optional<AnvilQueue.QueueClaim> claim = queue.claim(workerId, visibilityTimeoutSeconds);
        if (claim.isEmpty()) return;

        UUID jobId = claim.get().jobId();
        log.info("Worker {} claimed job={}", workerId, jobId);

        Optional<Job> jobOpt = jobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            log.warn("Claimed job={} not in DB, acking to clear queue", jobId);
            queue.ack(jobId);
            return;
        }

        Job job = jobOpt.get();
        if (job.getStatus() != JobStatus.QUEUED && job.getStatus() != JobStatus.RETRYING) {
            log.warn("Job={} has unexpected status={}, acking", jobId, job.getStatus());
            queue.ack(jobId);
            return;
        }

        JobHandler<?, ?> handler;
        try {
            handler = handlerRegistry.getHandler(job.getJobType());
        } catch (UnknownJobTypeException e) {
            log.error("No handler for job type={}, job={}", job.getJobType(), jobId);
            handlePermanentFailure(job, "No handler registered for job type: " + job.getJobType(), null);
            queue.ack(jobId);
            return;
        }

        Worker worker = getOrCreateWorker();
        worker.setCurrentJobId(jobId);
        worker.setStatus(WorkerStatus.HEALTHY);
        workerRepository.save(worker);

        UUID workerUuid;
        try {
            workerUuid = UUID.fromString(workerId);
        } catch (IllegalArgumentException e) {
            workerUuid = worker.getId();
        }

        JobAttempt attempt = new JobAttempt(job, job.getAttemptCount() + 1, JobStatus.RUNNING);
        attempt.setWorkerId(workerUuid);
        attempt = attemptRepository.save(attempt);

        stateMachine.transition(job, JobStatus.RUNNING, workerUuid);
        job.setAttemptCount(job.getAttemptCount() + 1);
        Duration timeout = handler.defaultTimeout();
        job.setTimeoutAt(Instant.now().plus(timeout));
        job = jobRepository.save(job);

        log.info("Worker {} executing job={} type={} attempt={}", workerId, jobId, job.getJobType(), job.getAttemptCount());

        try {
            JobExecutionContext ctx = new JobExecutionContextImpl(job, job.getAttemptCount(), jobRepository);
            Object result = executeHandler(handler, job.getPayload(), ctx);

            if (job.getStatus() == JobStatus.CANCELLING || job.getStatus() == JobStatus.CANCELLED) {
                handleCancellation(job, attempt, workerUuid);
                queue.ack(jobId);
                return;
            }

            if (result == null) {
                handleCancellation(job, attempt, workerUuid);
                queue.ack(jobId);
                return;
            }

            stateMachine.transition(job, JobStatus.COMPLETED, workerUuid);
            job.setResult(result.toString());
            job.setProgressPct(100);
            job.setProgressMessage("Completed");
            jobRepository.save(job);

            attempt.setStatus(JobStatus.COMPLETED);
            attempt.setEndedAt(Instant.now());
            attemptRepository.save(attempt);

            queue.ack(jobId);
            log.info("Worker {} completed job={}", workerId, jobId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Worker {} interrupted during job={}", workerId, jobId);
            handleTransientFailure(job, attempt, workerUuid, "Worker interrupted", e);
            queue.nack(jobId);
        } catch (Exception e) {
            log.error("Worker {} failed on job={}", workerId, jobId, e);
            handleTransientFailure(job, attempt, workerUuid, e.getMessage(), e);
            queue.nack(jobId);
        } finally {
            Worker w = getOrCreateWorker();
            w.setCurrentJobId(null);
            workerRepository.save(w);
        }
    }

    private void handleTransientFailure(Job job, JobAttempt attempt, UUID workerUuid, String message, Exception e) {
        try {
            String errorDetail = message != null ? message : "Unknown error";
            String stackTrace = getStackTrace(e);

            stateMachine.transition(job, JobStatus.FAILED, workerUuid, errorDetail);
            job.setErrorMessage(errorDetail);
            jobRepository.save(job);

            attempt.setStatus(JobStatus.FAILED);
            attempt.setEndedAt(Instant.now());
            attempt.setError(errorDetail);
            attempt.setStackTrace(stackTrace);
            attemptRepository.save(attempt);

            if (job.getAttemptCount() >= job.getMaxRetries()) {
                log.warn("Job={} exhausted {} retries, marking FAILED_PERMANENTLY", job.getId(), job.getMaxRetries());
                stateMachine.transition(job, JobStatus.FAILED_PERMANENTLY, workerUuid, "Max retries exhausted");
                job.setCompletedAt(Instant.now());
                jobRepository.save(job);
                deadLetterService.createEntry(job, "Max retries exhausted after " + job.getAttemptCount() + " attempts");
            } else {
                stateMachine.transition(job, JobStatus.RETRYING, workerUuid);
                int retryIndex = Math.min(job.getAttemptCount(), retryBackoffSeconds.length - 1);
                long delaySeconds = retryBackoffSeconds[retryIndex];
                job.setNextRetryAt(Instant.now().plusSeconds(delaySeconds));
                jobRepository.save(job);
                log.info("Job={} scheduled for retry (attempt {}/{}), backoff={}s",
                        job.getId(), job.getAttemptCount(), job.getMaxRetries(), delaySeconds);
            }
        } catch (Exception ex) {
            log.error("Failed to record failure for job={}: {}", job.getId(), ex.getMessage());
        }
    }

    private void handlePermanentFailure(Job job, String message, Exception e) {
        UUID workerUuid;
        try {
            workerUuid = UUID.fromString(workerId);
        } catch (IllegalArgumentException ex) {
            workerUuid = job.getUserId();
        }

        if (job.getStatus() == JobStatus.QUEUED) {
            stateMachine.transition(job, JobStatus.RUNNING, workerUuid);
        }
        stateMachine.transition(job, JobStatus.FAILED, workerUuid, message);
        job.setErrorMessage(message);
        job.setAttemptCount(job.getAttemptCount() + 1);
        jobRepository.save(job);

        stateMachine.transition(job, JobStatus.FAILED_PERMANENTLY, workerUuid, message);
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
        deadLetterService.createEntry(job, message);
    }

    private void handleCancellation(Job job, JobAttempt attempt, UUID workerUuid) {
        try {
            Job current = jobRepository.findById(job.getId()).orElse(job);
            if (current.getStatus() == JobStatus.CANCELLING) {
                stateMachine.transition(current, JobStatus.CANCELLED, workerUuid, "Cancelled during execution");
                jobRepository.save(current);
            }
            attempt.setStatus(JobStatus.CANCELLED);
            attempt.setEndedAt(Instant.now());
            attempt.setError("Cancelled by user");
            attemptRepository.save(attempt);
            log.info("Job={} cancelled during execution", job.getId());
        } catch (Exception e) {
            log.error("Failed to record cancellation for job={}: {}", job.getId(), e.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${worker.heartbeat-interval-ms:10000}", initialDelay = 2000)
    public void heartbeat() {
        Worker worker = getOrCreateWorker();
        worker.setLastHeartbeatAt(Instant.now());
        workerRepository.save(worker);
    }

    private Worker getOrCreateWorker() {
        return workerRepository.findByHostname(workerId)
                .orElseGet(() -> workerRepository.save(new Worker(workerId)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object executeHandler(JobHandler handler, String payload, JobExecutionContext ctx) throws Exception {
        return handler.execute(payload, ctx);
    }

    private String getStackTrace(Exception e) {
        if (e == null) return null;
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }
}
