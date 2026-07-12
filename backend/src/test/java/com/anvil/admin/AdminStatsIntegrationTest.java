package com.anvil.admin;

import com.anvil.audit.AuditLogEntryRepository;
import com.anvil.job.domain.*;
import com.anvil.job.repository.DeadLetterEntryRepository;
import com.anvil.job.repository.JobAttemptRepository;
import com.anvil.job.repository.JobRepository;
import com.anvil.queue.AnvilQueue;
import com.anvil.queue.OutboxEntry;
import com.anvil.queue.OutboxEntryRepository;
import com.anvil.queue.OutboxRelay;
import com.anvil.worker.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties = {
        "worker.id=f0000000-0000-0000-0000-000000000001",
        "worker.poll-interval-ms=999999999",
        "worker.heartbeat-interval-ms=999999999",
        "worker.visibility-timeout-seconds=5",
        "watchdog.interval-ms=999999999",
        "queue.relay.interval-ms=999999999",
        "scheduler.retry-interval-ms=999999999",
        "scheduler.scheduled-scan-interval-ms=999999999",
        "spring.flyway.enabled=true"
})
class AdminStatsIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("anvil_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getFirstMappedPort());
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private AdminStatsService statsService;
    @Autowired private AdminWorkerService workerService;
    @Autowired private JobRepository jobRepository;
    @Autowired private WorkerRepository workerRepository;
    @Autowired private DeadLetterEntryRepository dlqRepository;
    @Autowired private AuditLogEntryRepository auditLogRepository;
    @Autowired private OutboxEntryRepository outboxRepository;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private AnvilQueue queue;
    @Autowired private WorkerRunner workerRunner;

    private UUID testUserId;

    @BeforeEach
    void setUp() {
        jdbc.execute("DELETE FROM outbox_entries");
        jdbc.execute("DELETE FROM job_attempts");
        jdbc.execute("DELETE FROM dead_letter_entries");
        jdbc.execute("DELETE FROM audit_log_entries");
        jdbc.execute("DELETE FROM jobs");
        jdbc.execute("DELETE FROM workers");
        jdbc.execute("DELETE FROM notifications");
        jdbc.execute("DELETE FROM refresh_tokens");
        jdbc.execute("DELETE FROM users");

        testUserId = UUID.randomUUID();
        jdbc.execute("INSERT INTO users (id, email, password_hash, role, is_active) VALUES ('"
                + testUserId + "', 'statstest" + System.nanoTime() + "@test.com', 'hash', 'ADMIN', true)");
    }

    @Test
    void overview_reflectsSeededDataExactly() {
        Instant now = Instant.now();

        Job highQueued = new Job(testUserId, "CSV_IMPORT", "{}", JobStatus.QUEUED);
        highQueued.setPriority(JobPriority.HIGH);
        jobRepository.saveAndFlush(highQueued);

        Job medQueued = new Job(testUserId, "EMAIL_CAMPAIGN", "{}", JobStatus.QUEUED);
        medQueued.setPriority(JobPriority.MEDIUM);
        jobRepository.saveAndFlush(medQueued);

        Job lowQueued = new Job(testUserId, "FILE_COMPRESSION", "{}", JobStatus.QUEUED);
        lowQueued.setPriority(JobPriority.LOW);
        jobRepository.saveAndFlush(lowQueued);

        Job created = new Job(testUserId, "AI_CONTENT_GENERATION", "{}", JobStatus.CREATED);
        created.setPriority(JobPriority.LOW);
        jobRepository.saveAndFlush(created);

        Job running = new Job(testUserId, "REPORT_GENERATION", "{}", JobStatus.RUNNING);
        running.setPriority(JobPriority.HIGH);
        running.setStartedAt(now.minusSeconds(30));
        jobRepository.saveAndFlush(running);

        Job completed = new Job(testUserId, "CSV_IMPORT", "{}", JobStatus.COMPLETED);
        completed.setPriority(JobPriority.MEDIUM);
        completed.setStartedAt(now.minusSeconds(60));
        completed.setCompletedAt(now.minusSeconds(10));
        jobRepository.saveAndFlush(completed);

        Job failed = new Job(testUserId, "IMAGE_PROCESSING", "{}", JobStatus.FAILED);
        failed.setPriority(JobPriority.LOW);
        failed.setStartedAt(now.minusSeconds(120));
        failed.setCompletedAt(now.minusSeconds(90));
        jobRepository.saveAndFlush(failed);

        Worker healthy = new Worker("stats-worker-healthy-" + System.nanoTime());
        healthy.setStatus(WorkerStatus.HEALTHY);
        workerRepository.saveAndFlush(healthy);

        Worker paused = new Worker("stats-worker-paused-" + System.nanoTime());
        paused.setStatus(WorkerStatus.PAUSED);
        workerRepository.saveAndFlush(paused);

        Map<String, Object> overview = statsService.getOverview();

        @SuppressWarnings("unchecked")
        Map<String, Object> waiting = (Map<String, Object>) overview.get("jobsWaitingByPriority");
        assertEquals(1L, waiting.get("HIGH"), "HIGH waiting");
        assertEquals(1L, waiting.get("MEDIUM"), "MEDIUM waiting");
        assertEquals(2L, waiting.get("LOW"), "LOW waiting (QUEUED + CREATED)");

        assertEquals(1L, overview.get("jobsRunning"));
        assertEquals(1L, overview.get("jobsCompletedLast1h"));
        assertEquals(1L, overview.get("jobsCompletedLast24h"));
        assertEquals(1L, overview.get("jobsFailedLast1h"));
        assertEquals(1L, overview.get("jobsFailedLast24h"));

        assertEquals(1L, overview.get("workersOnline"));
        assertEquals(1L, overview.get("workersPaused"));
        assertEquals(0L, overview.get("workersUnhealthy"));

        assertEquals(4L, overview.get("queueSize"));

        Number avgTime = (Number) overview.get("averageProcessingTimeSeconds");
        assertNotNull(avgTime);
        assertTrue(avgTime.doubleValue() > 0, "Average processing time should be positive");

        @SuppressWarnings("unchecked")
        Map<String, Double> avgByType = (Map<String, Double>) overview.get("averageProcessingTimeByJobType");
        assertTrue(avgByType.containsKey("CSV_IMPORT"));
        assertFalse(avgByType.containsKey("IMAGE_PROCESSING"),
                "IMAGE_PROCESSING was FAILED, not in completed averages");
    }

    @Test
    void workerPaused_doesNotClaimJobs() throws Exception {
        for (JobPriority p : JobPriority.values()) {
            while (queue.claim("drain-pause-test", 1).isPresent()) {}
        }

        workerRunner.poll();

        Worker worker = workerRepository.findByHostname("f0000000-0000-0000-0000-000000000001").orElseThrow();
        worker.setCurrentJobId(null);
        worker.setStatus(WorkerStatus.PAUSED);
        workerRepository.saveAndFlush(worker);

        Job job = new Job(testUserId, "AI_CONTENT_GENERATION", "{}", JobStatus.QUEUED);
        job.setMaxRetries(1);
        job = jobRepository.saveAndFlush(job);
        queue.enqueue(job.getId(), JobPriority.MEDIUM);

        workerRunner.poll();

        Job afterPoll = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, afterPoll.getStatus(),
                "Paused worker should not claim jobs");

        worker.setStatus(WorkerStatus.HEALTHY);
        worker.setCurrentJobId(null);
        workerRepository.saveAndFlush(worker);

        workerRunner.poll();

        Job afterResume = jobRepository.findById(job.getId()).orElseThrow();
        assertNotEquals(JobStatus.QUEUED, afterResume.getStatus(),
                "Resumed worker should claim the job");
    }
}
