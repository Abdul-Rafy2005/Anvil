package com.anvil.queue;

import com.anvil.job.domain.JobPriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final int MAX_RETRIES = 5;

    private final OutboxEntryRepository outboxRepository;
    private final AnvilQueue queue;

    @Value("${queue.relay.retry-base-delay-ms:1000}")
    private long retryBaseDelayMs;

    public OutboxRelay(OutboxEntryRepository outboxRepository, AnvilQueue queue) {
        this.outboxRepository = outboxRepository;
        this.queue = queue;
    }

    @Scheduled(fixedDelayString = "${queue.relay.interval-ms:1000}")
    @Transactional
    public void relay() {
        List<OutboxEntry> entries = outboxRepository.findRetryableEntries();
        for (OutboxEntry entry : entries) {
            try {
                queue.enqueue(entry.getJobId(), entry.getPriority());
                entry.setStatus(OutboxStatus.RELAYED);
                entry.setProcessedAt(Instant.now());
                outboxRepository.save(entry);
                log.debug("Relayed outbox entry: jobId={}", entry.getJobId());
            } catch (Exception e) {
                int newRetryCount = entry.getRetryCount() + 1;
                entry.setRetryCount(newRetryCount);
                if (newRetryCount >= MAX_RETRIES) {
                    log.error("Outbox entry permanently failed after {} retries: jobId={}", MAX_RETRIES, entry.getJobId(), e);
                    entry.setStatus(OutboxStatus.FAILED);
                    entry.setNextRetryAt(null);
                } else {
                    long delayMs = retryBaseDelayMs * (1L << (newRetryCount - 1));
                    entry.setStatus(OutboxStatus.FAILED);
                    entry.setNextRetryAt(Instant.now().plusMillis(delayMs));
                    log.warn("Outbox relay failed (retry {}/{}), next retry in {}ms: jobId={}",
                            newRetryCount, MAX_RETRIES, delayMs, entry.getJobId(), e);
                }
                outboxRepository.save(entry);
            }
        }
    }
}
