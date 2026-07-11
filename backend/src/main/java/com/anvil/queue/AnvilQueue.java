package com.anvil.queue;

import com.anvil.job.domain.JobPriority;
import java.util.Optional;
import java.util.UUID;

public interface AnvilQueue {

    void enqueue(UUID jobId, JobPriority priority);

    Optional<QueueClaim> claim(String workerId, int visibilityTimeoutSeconds);

    void ack(UUID jobId);

    void nack(UUID jobId);

    long size(JobPriority priority);

    record QueueClaim(UUID jobId, String claimedBy) {}
}
