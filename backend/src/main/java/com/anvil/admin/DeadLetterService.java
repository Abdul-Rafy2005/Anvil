package com.anvil.admin;

import com.anvil.audit.AuditLogService;
import com.anvil.job.domain.DeadLetterEntry;
import com.anvil.job.domain.Job;
import com.anvil.job.domain.JobStatus;
import com.anvil.job.domain.JobStateMachine;
import com.anvil.job.repository.DeadLetterEntryRepository;
import com.anvil.job.repository.JobAttemptRepository;
import com.anvil.job.repository.JobRepository;
import com.anvil.queue.AnvilQueue;
import com.anvil.queue.OutboxEntry;
import com.anvil.queue.OutboxEntryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final DeadLetterEntryRepository dlqRepository;
    private final JobRepository jobRepository;
    private final JobAttemptRepository attemptRepository;
    private final JobStateMachine stateMachine;
    private final OutboxEntryRepository outboxRepository;
    private final AuditLogService auditLogService;

    public DeadLetterService(DeadLetterEntryRepository dlqRepository,
                             JobRepository jobRepository,
                             JobAttemptRepository attemptRepository,
                             JobStateMachine stateMachine,
                             OutboxEntryRepository outboxRepository,
                             AuditLogService auditLogService) {
        this.dlqRepository = dlqRepository;
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
        this.stateMachine = stateMachine;
        this.outboxRepository = outboxRepository;
        this.auditLogService = auditLogService;
    }

    public void createEntry(Job job, String reason) {
        if (dlqRepository.existsByJobId(job.getId())) return;

        String failureHistory = buildFailureHistory(job);
        DeadLetterEntry entry = new DeadLetterEntry(
                job.getId(), job.getJobType(), job.getUserId(), reason, failureHistory);
        dlqRepository.save(entry);
        log.warn("DLQ entry created: job={} type={} reason={}", job.getId(), job.getJobType(), reason);
    }

    @Transactional
    public DeadLetterEntry requeue(UUID entryId, UUID adminUserId) {
        DeadLetterEntry entry = dlqRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("DLQ entry not found: " + entryId));

        Job job = jobRepository.findById(entry.getJobId())
                .orElseThrow(() -> new IllegalArgumentException("Job not found: " + entry.getJobId()));

        job.setAttemptCount(0);
        job.setStatus(JobStatus.QUEUED);
        job.setErrorMessage(null);
        job.setNextRetryAt(null);
        jobRepository.save(job);

        entry.setResolvedBy(adminUserId);
        entry.setResolvedAction("REQUEUED");
        entry.setResolvedAt(Instant.now());
        dlqRepository.save(entry);

        OutboxEntry outboxEntry = new OutboxEntry(job.getId(), job.getPriority());
        outboxRepository.deleteByJobId(job.getId());
        outboxRepository.flush();
        outboxRepository.save(outboxEntry);

        auditLogService.log(adminUserId, "DLQ_REQUEUE", "Job", job.getId(),
                "Requeued from DLQ entry " + entryId);
        log.info("DLQ requeue: job={} by admin={}", job.getId(), adminUserId);
        return entry;
    }

    @Transactional
    public DeadLetterEntry discard(UUID entryId, UUID adminUserId) {
        DeadLetterEntry entry = dlqRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("DLQ entry not found: " + entryId));

        entry.setResolvedBy(adminUserId);
        entry.setResolvedAction("DISCARDED");
        entry.setResolvedAt(Instant.now());
        dlqRepository.save(entry);

        auditLogService.log(adminUserId, "DLQ_DISCARD", "Job", entry.getJobId(),
                "Discarded DLQ entry " + entryId);
        log.info("DLQ discard: entry={} by admin={}", entryId, adminUserId);
        return entry;
    }

    public Page<DeadLetterEntry> listEntries(String jobType, Boolean resolved, Pageable pageable) {
        return dlqRepository.findAllFiltered(jobType, resolved, pageable);
    }

    public DeadLetterEntry getEntry(UUID entryId) {
        return dlqRepository.findById(entryId)
                .orElseThrow(() -> new IllegalArgumentException("DLQ entry not found: " + entryId));
    }

    private String buildFailureHistory(Job job) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("jobId", job.getId().toString());
            root.put("jobType", job.getJobType());
            root.put("finalStatus", job.getStatus().name());
            root.put("attemptCount", job.getAttemptCount());
            root.put("maxRetries", job.getMaxRetries());

            var attempts = attemptRepository.findAll().stream()
                    .filter(a -> a.getJob().getId().equals(job.getId()))
                    .sorted((a, b) -> Integer.compare(a.getAttemptNumber(), b.getAttemptNumber()))
                    .toList();

            ArrayNode attemptsArray = mapper.createArrayNode();
            for (var attempt : attempts) {
                ObjectNode aNode = mapper.createObjectNode();
                aNode.put("attemptNumber", attempt.getAttemptNumber());
                aNode.put("status", attempt.getStatus().name());
                aNode.put("error", attempt.getError());
                aNode.put("stackTrace", attempt.getStackTrace());
                aNode.put("workerId", attempt.getWorkerId() != null ? attempt.getWorkerId().toString() : null);
                aNode.put("startedAt", attempt.getStartedAt() != null ? attempt.getStartedAt().toString() : null);
                aNode.put("endedAt", attempt.getEndedAt() != null ? attempt.getEndedAt().toString() : null);
                attemptsArray.add(aNode);
            }
            root.set("attempts", attemptsArray);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\": \"Failed to build failure history\"}";
        }
    }
}
