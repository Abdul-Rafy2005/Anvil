package com.anvil.worker;

import com.anvil.admin.DeadLetterService;
import com.anvil.job.domain.Job;
import com.anvil.job.domain.JobStatus;
import com.anvil.job.domain.JobStateMachine;
import com.anvil.job.repository.JobRepository;
import com.anvil.queue.AnvilQueue;
import com.anvil.queue.RedisQueue;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class WorkerWatchdog {

    private static final Logger log = LoggerFactory.getLogger(WorkerWatchdog.class);

    private final WorkerRepository workerRepository;
    private final JobRepository jobRepository;
    private final AnvilQueue queue;
    private final RedisQueue redisQueue;
    private final JobStateMachine stateMachine;
    private final DeadLetterService deadLetterService;
    private final long[] retryBackoffSeconds;

    @Value("${worker.heartbeat-timeout-ms:30000}")
    private long heartbeatTimeoutMs;

    public WorkerWatchdog(WorkerRepository workerRepository,
                          JobRepository jobRepository,
                          AnvilQueue queue,
                          RedisQueue redisQueue,
                          JobStateMachine stateMachine,
                          DeadLetterService deadLetterService,
                          @Value("${worker.retry-backoff-seconds:30,60,120,240}") String retryBackoffCsv) {
        this.workerRepository = workerRepository;
        this.jobRepository = jobRepository;
        this.queue = queue;
        this.redisQueue = redisQueue;
        this.stateMachine = stateMachine;
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

    @PostConstruct
    @Transactional
    public void reEnqueueLostJobs() {
        List<Job> queuedJobs = jobRepository.findByStatus(JobStatus.QUEUED);
        int reEnqueued = 0;
        for (Job job : queuedJobs) {
            queue.enqueue(job.getId(), job.getPriority());
            reEnqueued++;
        }
        if (reEnqueued > 0) {
            log.info("Startup recovery: re-enqueued {} QUEUED jobs lost from Redis", reEnqueued);
        }
    }

    @Scheduled(fixedDelayString = "${watchdog.interval-ms:15000}", initialDelay = 5000)
    @Transactional
    public void checkStaleWorkers() {
        Instant threshold = Instant.now().minusMillis(heartbeatTimeoutMs);
        List<Worker> allWorkers = workerRepository.findAll();

        for (Worker worker : allWorkers) {
            if (worker.getLastHeartbeatAt() == null) continue;
            if (worker.getLastHeartbeatAt().isBefore(threshold)
                    && worker.getStatus() != WorkerStatus.UNHEALTHY) {
                log.warn("Worker {} heartbeat stale, marking UNHEALTHY", worker.getHostname());
                worker.setStatus(WorkerStatus.UNHEALTHY);
                workerRepository.save(worker);

                if (worker.getCurrentJobId() != null) {
                    UUID jobId = worker.getCurrentJobId();
                    jobRepository.findById(jobId).ifPresent(job -> {
                        if (job.getStatus() == JobStatus.RUNNING) {
                            log.warn("Reclaiming job={} from stale worker={}", jobId, worker.getHostname());
                            failAndRetryJob(job, "Worker " + worker.getHostname() + " became stale");
                            queue.nack(jobId);
                        }
                    });
                }
            }
        }
    }

    @Scheduled(fixedDelayString = "${watchdog.interval-ms:15000}", initialDelay = 7000)
    @Transactional
    public void checkTimedOutJobs() {
        List<Job> timedOutJobs = jobRepository.findTimedOutJobs(Instant.now());
        for (Job job : timedOutJobs) {
            log.warn("Job={} timed out (timeoutAt={}), force-failing", job.getId(), job.getTimeoutAt());
            failAndRetryJob(job, "Job execution timed out after " + job.getTimeoutSeconds() + " seconds");
        }
    }

    @Scheduled(fixedDelayString = "${watchdog.interval-ms:15000}", initialDelay = 10000)
    @Transactional
    public void reclaimOrphanedJobs() {
        List<Job> runningJobs = jobRepository.findByStatus(JobStatus.RUNNING);
        for (Job job : runningJobs) {
            if (!redisQueue.isClaimed(job.getId())) {
                log.warn("Job={} orphaned (RUNNING but not in Redis claimed set), reclaiming", job.getId());
                try {
                    UUID actor = job.getUserId();
                    stateMachine.transition(job, JobStatus.FAILED, actor, "Worker crashed mid-job");
                    job.setErrorMessage("Worker crashed mid-job, job orphaned");

                    if (job.getAttemptCount() >= job.getMaxRetries()) {
                        log.warn("Job={} exhausted retries, marking FAILED_PERMANENTLY", job.getId());
                        stateMachine.transition(job, JobStatus.FAILED_PERMANENTLY, actor, "Exhausted retries after worker crash");
                        job.setCompletedAt(Instant.now());
                        jobRepository.save(job);
                        deadLetterService.createEntry(job, "Worker crashed, retries exhausted");
                    } else {
                        stateMachine.transition(job, JobStatus.RETRYING, actor, "Re-enqueueing after worker crash");
                        job.setNextRetryAt(Instant.now());
                        jobRepository.save(job);
                        log.info("Job={} scheduled for immediate retry after worker crash", job.getId());
                    }
                } catch (Exception e) {
                    log.error("Failed to reclaim orphaned job={}: {}", job.getId(), e.getMessage());
                }
            }
        }
    }

    private void failAndRetryJob(Job job, String reason) {
        try {
            UUID systemActor = job.getUserId();

            stateMachine.transition(job, JobStatus.FAILED, systemActor, reason);
            job.setErrorMessage(reason);
            jobRepository.save(job);

            if (job.getAttemptCount() >= job.getMaxRetries()) {
                log.warn("Job={} exhausted retries after timeout, marking FAILED_PERMANENTLY", job.getId());
                stateMachine.transition(job, JobStatus.FAILED_PERMANENTLY, systemActor, reason);
                job.setCompletedAt(Instant.now());
                jobRepository.save(job);
                deadLetterService.createEntry(job, reason);
            } else {
                stateMachine.transition(job, JobStatus.RETRYING, systemActor);
                int retryIndex = Math.min(job.getAttemptCount(), retryBackoffSeconds.length - 1);
                long delaySeconds = retryBackoffSeconds[retryIndex];
                job.setNextRetryAt(Instant.now().plusSeconds(delaySeconds));
                jobRepository.save(job);
                log.info("Job={} scheduled for retry after timeout, backoff={}s", job.getId(), delaySeconds);
            }
        } catch (Exception e) {
            log.error("Failed to handle timeout for job={}: {}", job.getId(), e.getMessage());
        }
    }
}
