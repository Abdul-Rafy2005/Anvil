package com.anvil.job.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = Instant.now();
    }

    public IdempotencyKey() {}

    public IdempotencyKey(String idempotencyKey, UUID userId, UUID jobId) {
        this.idempotencyKey = idempotencyKey;
        this.userId = userId;
        this.jobId = jobId;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public UUID getUserId() { return userId; }
    public UUID getJobId() { return jobId; }
    public Instant getCreatedAt() { return createdAt; }
}
