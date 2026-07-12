package com.anvil.scheduler;

import com.anvil.job.domain.Job;
import com.anvil.job.domain.JobStateMachine;
import com.anvil.job.domain.JobStatus;
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
public class ScheduledJobScheduler {

    private static final Logger log = LoggerFactory.getLogger(ScheduledJobScheduler.class);

    private static final int BATCH_SIZE = 50;

    private final JobRepository jobRepository;
    private final JobStateMachine stateMachine;
    private final OutboxEntryRepository outboxRepository;
    private final CronSchedulerHelper cronHelper;

    public ScheduledJobScheduler(JobRepository jobRepository,
                                  JobStateMachine stateMachine,
                                  OutboxEntryRepository outboxRepository,
                                  CronSchedulerHelper cronHelper) {
        this.jobRepository = jobRepository;
        this.stateMachine = stateMachine;
        this.outboxRepository = outboxRepository;
        this.cronHelper = cronHelper;
    }

    @Transactional
    @Scheduled(fixedDelayString = "${scheduler.scheduled-scan-interval-ms:5000}",
               initialDelayString = "${scheduler.scheduled-scan-interval-ms:5000}")
    public void processScheduledJobs() {
        Instant now = Instant.now();
        processDueScheduledJobs(now);
        processDueCronJobs(now);
    }

    private void processDueScheduledJobs(Instant now) {
        List<Job> dueJobs = jobRepository.findDueScheduledJobs(now, BATCH_SIZE);
        for (Job job : dueJobs) {
            MDC.put("jobId", job.getId().toString());
            try {
                stateMachine.transition(job, JobStatus.QUEUED, job.getUserId(), "Scheduler: scheduled job due");
                job.setNextFireAt(null);
                outboxRepository.save(new OutboxEntry(job.getId(), job.getPriority()));
                jobRepository.save(job);
                log.info("Scheduler: scheduled job={} enqueued (fireAt={})", job.getId(), job.getScheduledAt());
            } catch (Exception e) {
                log.error("Scheduler: failed to enqueue scheduled job={}: {}", job.getId(), e.getMessage());
            } finally {
                MDC.remove("jobId");
            }
        }
    }

    private void processDueCronJobs(Instant now) {
        List<Job> dueJobs = jobRepository.findDueCronJobs(now, BATCH_SIZE);
        for (Job job : dueJobs) {
            MDC.put("jobId", job.getId().toString());
            try {
                stateMachine.transition(job, JobStatus.QUEUED, job.getUserId(), "Scheduler: cron job due");
                Instant nextFireAt = cronHelper.getNextFireTime(job.getCronExpression(), now);
                job.setNextFireAt(nextFireAt);
                outboxRepository.save(new OutboxEntry(job.getId(), job.getPriority()));
                jobRepository.save(job);
                log.info("Scheduler: cron job={} enqueued, nextFire={}", job.getId(), nextFireAt);
            } catch (Exception e) {
                log.error("Scheduler: failed to enqueue cron job={}: {}", job.getId(), e.getMessage());
            } finally {
                MDC.remove("jobId");
            }
        }
    }
}
