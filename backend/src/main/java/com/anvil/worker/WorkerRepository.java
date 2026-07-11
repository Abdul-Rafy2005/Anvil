package com.anvil.worker;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkerRepository extends JpaRepository<Worker, UUID> {
    Optional<Worker> findByHostname(String hostname);
    List<Worker> findByStatusNot(WorkerStatus status);
}
