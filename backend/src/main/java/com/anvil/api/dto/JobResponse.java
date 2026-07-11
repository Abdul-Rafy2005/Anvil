package com.anvil.api.dto;

import com.anvil.job.domain.JobPriority;
import com.anvil.job.domain.JobStatus;
import java.time.Instant;
import java.util.UUID;

public record JobResponse(
    UUID id,
    UUID userId,
    String jobType,
    String payload,
    JobStatus status,
    JobPriority priority,
    Integer progressPct,
    String progressMessage,
    String result,
    String errorMessage,
    int attemptCount,
    int maxRetries,
    Instant createdAt,
    Instant updatedAt,
    Instant startedAt,
    Instant completedAt
) {}
