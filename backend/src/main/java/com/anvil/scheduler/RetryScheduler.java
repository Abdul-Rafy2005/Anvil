package com.anvil.scheduler;

import com.anvil.job.domain.Job;
import com.anvil.job.domain.JobPriority;
import com.anvil.job.domain.JobStatus;
import com.anvil.job.domain.JobStateMachine;
import com.anvil.job.repository.JobRepository;
import com.anvil.queue.AnvilQueue;
import com.anvil.queue.OutboxEntry;
import com.anvil.queue.OutboxEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;

@Component
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);

    private final JobRepository jobRepository;
    private final JobStateMachine stateMachine;
    private final OutboxEntryRepository outboxRepository;

    public RetryScheduler(JobRepository jobRepository,
                          JobStateMachine stateMachine,
                          OutboxEntryRepository outboxRepository) {
        this.jobRepository = jobRepository;
        this.stateMachine = stateMachine;
        this.outboxRepository = outboxRepository;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${scheduler.retry-interval-ms:5000}",
               initialDelayString = "${scheduler.retry-interval-ms:5000}")
    public void processRetryingJobs() {
        List<Job> dueJobs = jobRepository.findRetryingJobsDue(Instant.now());
        for (Job job : dueJobs) {
            MDC.put("jobId", job.getId().toString());
            try {
                stateMachine.transition(job, JobStatus.QUEUED, job.getUserId(), "Retry scheduler: re-enqueueing");
                job.setNextRetryAt(null);
                outboxRepository.deleteByJobId(job.getId());
                outboxRepository.flush();
                OutboxEntry outbox = new OutboxEntry(job.getId(), job.getPriority());
                outboxRepository.save(outbox);
                jobRepository.save(job);
                log.info("Retry scheduler: job={} re-enqueued (attempt {}/{})",
                        job.getId(), job.getAttemptCount(), job.getMaxRetries());
            } catch (Exception e) {
                log.error("Retry scheduler: failed to re-enqueue job={}: {}", job.getId(), e.getMessage());
            } finally {
                MDC.remove("jobId");
            }
        }
    }
}
