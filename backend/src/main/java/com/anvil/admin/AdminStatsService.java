package com.anvil.admin;

import com.anvil.job.domain.JobPriority;
import com.anvil.job.domain.JobStatus;
import com.anvil.job.repository.DeadLetterEntryRepository;
import com.anvil.job.repository.JobRepository;
import com.anvil.worker.WorkerRepository;
import com.anvil.worker.WorkerStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AdminStatsService {

    private final JobRepository jobRepository;
    private final WorkerRepository workerRepository;
    private final DeadLetterEntryRepository dlqRepository;

    public AdminStatsService(JobRepository jobRepository,
                             WorkerRepository workerRepository,
                             DeadLetterEntryRepository dlqRepository) {
        this.jobRepository = jobRepository;
        this.workerRepository = workerRepository;
        this.dlqRepository = dlqRepository;
    }

    public Map<String, Object> getOverview() {
        Instant now = Instant.now();
        Instant oneHourAgo = now.minusSeconds(3600);
        Instant twentyFourHoursAgo = now.minusSeconds(86400);

        long queuedJobs = jobRepository.countByStatus(JobStatus.QUEUED)
                + jobRepository.countByStatus(JobStatus.CREATED);
        long runningJobs = jobRepository.countByStatus(JobStatus.RUNNING);

        long completedLast1h = jobRepository.countByStatusInAndCompletedAtSince(
                java.util.List.of(JobStatus.COMPLETED), oneHourAgo);
        long completedLast24h = jobRepository.countByStatusInAndCompletedAtSince(
                java.util.List.of(JobStatus.COMPLETED), twentyFourHoursAgo);

        long failedLast1h = jobRepository.countByStatusInAndCompletedAtSince(
                java.util.List.of(JobStatus.FAILED, JobStatus.FAILED_PERMANENTLY), oneHourAgo);
        long failedLast24h = jobRepository.countByStatusInAndCompletedAtSince(
                java.util.List.of(JobStatus.FAILED, JobStatus.FAILED_PERMANENTLY), twentyFourHoursAgo);

        long workersHealthy = workerRepository.countByStatus(WorkerStatus.HEALTHY);
        long workersPaused = workerRepository.countByStatus(WorkerStatus.PAUSED);
        long workersUnhealthy = workerRepository.countByStatus(WorkerStatus.UNHEALTHY);
        long totalWorkers = workersHealthy + workersPaused + workersUnhealthy;

        Number avgRaw = jobRepository.averageProcessingTimeOverall();
        Double avgProcessingTime = avgRaw != null ? avgRaw.doubleValue() : null;
        Map<String, Double> avgByType = new LinkedHashMap<>();
        for (Object[] row : jobRepository.averageProcessingTimePerJobType()) {
            Number val = (Number) row[1];
            avgByType.put((String) row[0], val != null ? Math.round(val.doubleValue() * 100.0) / 100.0 : 0.0);
        }

        long dlqSize = dlqRepository.count();

        long waitingHigh = jobRepository.countWaitingByPriority(JobPriority.HIGH);
        long waitingMedium = jobRepository.countWaitingByPriority(JobPriority.MEDIUM);
        long waitingLow = jobRepository.countWaitingByPriority(JobPriority.LOW);

        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("jobsWaitingByPriority", Map.of(
                "HIGH", waitingHigh,
                "MEDIUM", waitingMedium,
                "LOW", waitingLow));
        overview.put("jobsRunning", runningJobs);
        overview.put("jobsCompletedLast1h", completedLast1h);
        overview.put("jobsCompletedLast24h", completedLast24h);
        overview.put("jobsFailedLast1h", failedLast1h);
        overview.put("jobsFailedLast24h", failedLast24h);
        overview.put("workersOnline", workersHealthy);
        overview.put("workersPaused", workersPaused);
        overview.put("workersUnhealthy", workersUnhealthy);
        overview.put("averageProcessingTimeSeconds", avgProcessingTime != null
                ? Math.round(avgProcessingTime * 100.0) / 100.0 : 0.0);
        overview.put("averageProcessingTimeByJobType", avgByType);
        overview.put("queueSize", queuedJobs);
        overview.put("workerUtilizationPct", totalWorkers > 0
                ? Math.round((double) runningJobs / totalWorkers * 10000.0) / 100.0 : 0.0);
        overview.put("dlqSize", dlqSize);
        return overview;
    }
}
