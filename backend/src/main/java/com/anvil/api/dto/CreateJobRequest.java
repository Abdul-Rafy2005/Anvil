package com.anvil.api.dto;

import com.anvil.job.domain.JobPriority;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record CreateJobRequest(
    @NotBlank String jobType,
    String payload,
    JobPriority priority,
    @JsonFormat(shape = JsonFormat.Shape.STRING) Instant scheduledAt,
    String cronExpression
) {
    @AssertTrue(message = "scheduledAt and cronExpression are mutually exclusive")
    private boolean isSchedulingValid() {
        return !(scheduledAt != null && cronExpression != null && !cronExpression.isBlank());
    }
}
