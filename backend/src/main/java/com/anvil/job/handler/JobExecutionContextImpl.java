package com.anvil.job.handler;

import com.anvil.job.domain.Job;
import com.anvil.job.domain.JobStatus;
import com.anvil.job.repository.JobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Map;
import java.util.UUID;

public class JobExecutionContextImpl implements JobExecutionContext {

    private static final Logger log = LoggerFactory.getLogger(JobExecutionContextImpl.class);

    private final Job job;
    private final int attemptNumber;
    private final JobRepository jobRepository;
    private final SimpMessagingTemplate messagingTemplate;

    public JobExecutionContextImpl(Job job, int attemptNumber, JobRepository jobRepository) {
        this(job, attemptNumber, jobRepository, null);
    }

    public JobExecutionContextImpl(Job job, int attemptNumber, JobRepository jobRepository,
                                   SimpMessagingTemplate messagingTemplate) {
        this.job = job;
        this.attemptNumber = attemptNumber;
        this.jobRepository = jobRepository;
        this.messagingTemplate = messagingTemplate;
    }

    @Override
    public UUID jobId() {
        return job.getId();
    }

    @Override
    public int attemptNumber() {
        return attemptNumber;
    }

    @Override
    public void reportProgress(int pct, String message) {
        job.setProgressPct(Math.min(100, Math.max(0, pct)));
        job.setProgressMessage(message);
        jobRepository.save(job);
        log.debug("Job {} progress: {}% - {}", job.getId(), pct, message);

        if (messagingTemplate != null) {
            Map<String, Object> payload = Map.of(
                    "jobId", job.getId().toString(),
                    "progressPct", Math.min(100, Math.max(0, pct)),
                    "message", message,
                    "type", "PROGRESS"
            );
            messagingTemplate.convertAndSend("/topic/job/" + job.getId(), payload);
        }
    }

    @Override
    public boolean isCancellationRequested() {
        Job current = jobRepository.findById(job.getId()).orElse(job);
        return current.getStatus() == JobStatus.CANCELLING || current.getStatus() == JobStatus.CANCELLED;
    }
}
