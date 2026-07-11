package com.anvil.worker;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workers")
public class Worker {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String hostname;

    @JdbcTypeCode(SqlTypes.NAMED_ENUM)
    @Column(nullable = false)
    private WorkerStatus status = WorkerStatus.HEALTHY;

    @Column(name = "last_heartbeat_at", nullable = false)
    private Instant lastHeartbeatAt;

    @Column(name = "current_job_id")
    private UUID currentJobId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @PrePersist
    protected void onCreate() {
        this.startedAt = Instant.now();
        this.lastHeartbeatAt = Instant.now();
    }

    public Worker() {}

    public Worker(String hostname) {
        this.hostname = hostname;
    }

    public UUID getId() { return id; }
    public String getHostname() { return hostname; }
    public WorkerStatus getStatus() { return status; }
    public void setStatus(WorkerStatus status) { this.status = status; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Instant lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public UUID getCurrentJobId() { return currentJobId; }
    public void setCurrentJobId(UUID currentJobId) { this.currentJobId = currentJobId; }
    public Instant getStartedAt() { return startedAt; }
}
