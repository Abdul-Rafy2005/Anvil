package com.anvil.api.dto;

import com.anvil.job.domain.Job;
import com.anvil.job.domain.JobPriority;
import com.anvil.job.domain.JobStatus;
import java.time.Instant;
import java.util.UUID;

public record JobListResponse(
    UUID id,
    String jobType,
    JobStatus status,
    JobPriority priority,
    Integer progressPct,
    Instant createdAt
) {
    public static JobListResponse from(Job job) {
        return new JobListResponse(
                job.getId(), job.getJobType(), job.getStatus(),
                job.getPriority(), job.getProgressPct(), job.getCreatedAt());
    }
}
