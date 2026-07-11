package com.anvil.api.dto;

import com.anvil.job.domain.JobPriority;
import jakarta.validation.constraints.NotBlank;

public record CreateJobRequest(
    @NotBlank String jobType,
    String payload,
    JobPriority priority
) {}
