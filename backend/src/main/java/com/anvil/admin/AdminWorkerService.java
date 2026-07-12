package com.anvil.admin;

import com.anvil.audit.AuditLogService;
import com.anvil.worker.Worker;
import com.anvil.worker.WorkerRepository;
import com.anvil.worker.WorkerStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AdminWorkerService {

    private final WorkerRepository workerRepository;
    private final AuditLogService auditLogService;

    public AdminWorkerService(WorkerRepository workerRepository, AuditLogService auditLogService) {
        this.workerRepository = workerRepository;
        this.auditLogService = auditLogService;
    }

    public List<Map<String, Object>> listWorkers() {
        return workerRepository.findAllByOrderByStartedAtDesc().stream()
                .map(this::toWorkerResponse)
                .toList();
    }

    @Transactional
    public Map<String, Object> pauseWorker(UUID workerId, UUID adminUserId) {
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));
        worker.setStatus(WorkerStatus.PAUSED);
        workerRepository.save(worker);
        auditLogService.log(adminUserId, "WORKER_PAUSE", "Worker", workerId,
                "Paused worker " + worker.getHostname());
        return toWorkerResponse(worker);
    }

    @Transactional
    public Map<String, Object> resumeWorker(UUID workerId, UUID adminUserId) {
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));
        worker.setStatus(WorkerStatus.HEALTHY);
        workerRepository.save(worker);
        auditLogService.log(adminUserId, "WORKER_RESUME", "Worker", workerId,
                "Resumed worker " + worker.getHostname());
        return toWorkerResponse(worker);
    }

    @Transactional
    public Map<String, Object> restartWorker(UUID workerId, UUID adminUserId) {
        Worker worker = workerRepository.findById(workerId)
                .orElseThrow(() -> new IllegalArgumentException("Worker not found: " + workerId));
        worker.setStatus(WorkerStatus.HEALTHY);
        worker.setCurrentJobId(null);
        worker.setLastHeartbeatAt(Instant.now());
        workerRepository.save(worker);
        auditLogService.log(adminUserId, "WORKER_RESTART", "Worker", workerId,
                "Restarted worker " + worker.getHostname());
        return toWorkerResponse(worker);
    }

    private Map<String, Object> toWorkerResponse(Worker worker) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", worker.getId().toString());
        response.put("hostname", worker.getHostname());
        response.put("status", worker.getStatus().name());
        response.put("currentJobId", worker.getCurrentJobId() != null ? worker.getCurrentJobId().toString() : null);
        response.put("heartbeatAgeSeconds", Duration.between(worker.getLastHeartbeatAt(), Instant.now()).getSeconds());
        response.put("startedAt", worker.getStartedAt().toString());
        return response;
    }
}
