package com.anvil.worker;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerRepository extends JpaRepository<Worker, UUID> {
    Optional<Worker> findByHostname(String hostname);
    List<Worker> findByStatusNot(WorkerStatus status);

    long countByStatus(WorkerStatus status);

    List<Worker> findAllByOrderByStartedAtDesc();

    @Query("SELECT COUNT(w) FROM Worker w WHERE w.lastHeartbeatAt < :threshold")
    long countUnhealthyByHeartbeat(@org.springframework.data.repository.query.Param("threshold") java.time.Instant threshold);
}
