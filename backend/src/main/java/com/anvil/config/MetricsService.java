package com.anvil.config;

import com.anvil.job.domain.JobPriority;
import com.anvil.job.repository.JobRepository;
import com.anvil.queue.AnvilQueue;
import com.anvil.worker.WorkerRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.concurrent.TimeUnit;

@Component
public class MetricsService {

    private final MeterRegistry meterRegistry;
    private final AnvilQueue queue;
    private final JobRepository jobRepository;
    private final WorkerRepository workerRepository;

    private Counter jobsSubmitted;
    private Counter jobsCompleted;
    private Counter jobsFailed;
    private Counter jobsDlq;
    private Timer jobSubmissionTimer;
    private Timer jobExecutionTimer;

    public MetricsService(MeterRegistry meterRegistry,
                          AnvilQueue queue,
                          JobRepository jobRepository,
                          WorkerRepository workerRepository) {
        this.meterRegistry = meterRegistry;
        this.queue = queue;
        this.jobRepository = jobRepository;
        this.workerRepository = workerRepository;
    }

    @PostConstruct
    public void init() {
        jobsSubmitted = Counter.builder("anvil.jobs.submitted")
                .description("Total jobs submitted")
                .register(meterRegistry);

        jobsCompleted = Counter.builder("anvil.jobs.completed")
                .description("Total jobs completed successfully")
                .register(meterRegistry);

        jobsFailed = Counter.builder("anvil.jobs.failed")
                .description("Total jobs that failed")
                .register(meterRegistry);

        jobsDlq = Counter.builder("anvil.jobs.dlq")
                .description("Total jobs sent to dead letter queue")
                .register(meterRegistry);

        jobSubmissionTimer = Timer.builder("anvil.jobs.submission.time")
                .description("Job submission latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        jobExecutionTimer = Timer.builder("anvil.jobs.execution.time")
                .description("Job execution latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);

        Gauge.builder("anvil.queue.depth", queue, q -> {
            try {
                return q.size(JobPriority.HIGH) + q.size(JobPriority.MEDIUM) + q.size(JobPriority.LOW);
            } catch (Exception e) {
                return 0;
            }
        }).description("Current queue depth across all priorities")
          .register(meterRegistry);

        Gauge.builder("anvil.queue.depth.high", queue, q -> {
            try { return q.size(JobPriority.HIGH); } catch (Exception e) { return 0; }
        }).description("Current HIGH priority queue depth").register(meterRegistry);

        Gauge.builder("anvil.queue.depth.medium", queue, q -> {
            try { return q.size(JobPriority.MEDIUM); } catch (Exception e) { return 0; }
        }).description("Current MEDIUM priority queue depth").register(meterRegistry);

        Gauge.builder("anvil.queue.depth.low", queue, q -> {
            try { return q.size(JobPriority.LOW); } catch (Exception e) { return 0; }
        }).description("Current LOW priority queue depth").register(meterRegistry);

        Gauge.builder("anvil.workers.total", workerRepository, repo -> repo.count())
                .description("Total registered workers")
                .register(meterRegistry);

        Gauge.builder("anvil.workers.healthy", workerRepository, repo -> repo.countByStatus(com.anvil.worker.WorkerStatus.HEALTHY))
                .description("Healthy workers")
                .register(meterRegistry);

        Gauge.builder("anvil.workers.paused", workerRepository, repo -> repo.countByStatus(com.anvil.worker.WorkerStatus.PAUSED))
                .description("Paused workers")
                .register(meterRegistry);

        Gauge.builder("anvil.workers.unhealthy", workerRepository, repo -> repo.countByStatus(com.anvil.worker.WorkerStatus.UNHEALTHY))
                .description("Unhealthy workers")
                .register(meterRegistry);

        Gauge.builder("anvil.jobs.queued", jobRepository, repo -> repo.countByStatus(com.anvil.job.domain.JobStatus.QUEUED))
                .description("Jobs in QUEUED status")
                .register(meterRegistry);

        Gauge.builder("anvil.jobs.running", jobRepository, repo -> repo.countByStatus(com.anvil.job.domain.JobStatus.RUNNING))
                .description("Jobs in RUNNING status")
                .register(meterRegistry);

        Gauge.builder("anvil.jobs.dlq.size", jobRepository, repo -> repo.countByStatus(com.anvil.job.domain.JobStatus.FAILED_PERMANENTLY))
                .description("Jobs in DLQ (FAILED_PERMANENTLY)")
                .register(meterRegistry);
    }

    public void recordJobSubmitted() {
        jobsSubmitted.increment();
    }

    public void recordJobCompleted() {
        jobsCompleted.increment();
    }

    public void recordJobFailed() {
        jobsFailed.increment();
    }

    public void recordJobDlq() {
        jobsDlq.increment();
    }

    public void recordSubmissionTime(long duration, TimeUnit unit) {
        jobSubmissionTimer.record(duration, unit);
    }

    public void recordExecutionTime(long duration, TimeUnit unit) {
        jobExecutionTimer.record(duration, unit);
    }
}
