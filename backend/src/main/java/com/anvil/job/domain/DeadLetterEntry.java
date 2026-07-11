package com.anvil.job.domain;

import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import org.hibernate.annotations.Type;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dead_letter_entries")
public class DeadLetterEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "job_id", nullable = false, unique = true)
    private UUID jobId;

    @Column(name = "job_type", nullable = false)
    private String jobType;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String reason;

    @Column(name = "failure_history", nullable = false, columnDefinition = "jsonb")
    @Type(JsonType.class)
    private String failureHistory;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_action")
    private String resolvedAction;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public DeadLetterEntry() {}

    public DeadLetterEntry(UUID jobId, String jobType, UUID userId, String reason, String failureHistory) {
        this.jobId = jobId;
        this.jobType = jobType;
        this.userId = userId;
        this.reason = reason;
        this.failureHistory = failureHistory;
    }

    public UUID getId() { return id; }
    public UUID getJobId() { return jobId; }
    public String getJobType() { return jobType; }
    public UUID getUserId() { return userId; }
    public String getReason() { return reason; }
    public String getFailureHistory() { return failureHistory; }
    public Instant getCreatedAt() { return createdAt; }
    public UUID getResolvedBy() { return resolvedBy; }
    public void setResolvedBy(UUID resolvedBy) { this.resolvedBy = resolvedBy; }
    public String getResolvedAction() { return resolvedAction; }
    public void setResolvedAction(String resolvedAction) { this.resolvedAction = resolvedAction; }
    public Instant getResolvedAt() { return resolvedAt; }
    public void setResolvedAt(Instant resolvedAt) { this.resolvedAt = resolvedAt; }
}
