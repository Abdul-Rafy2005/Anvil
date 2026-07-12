package com.anvil.worker;

import com.anvil.api.dto.CreateJobRequest;
import com.anvil.job.domain.*;
import com.anvil.job.handler.JobHandlerRegistry;
import com.anvil.job.repository.JobAttemptRepository;
import com.anvil.job.repository.JobRepository;
import com.anvil.job.service.JobService;
import com.anvil.queue.*;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(properties = {
        "worker.id=f0000000-0000-0000-0000-000000000001",
        "worker.poll-interval-ms=999999999",
        "worker.heartbeat-interval-ms=999999999",
        "worker.visibility-timeout-seconds=5",
        "worker.heartbeat-timeout-ms=2000",
        "watchdog.interval-ms=999999999",
        "queue.relay.interval-ms=999999999",
        "spring.flyway.enabled=true"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkerIntegrationTest {

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
    @Autowired private JobService jobService;
    @Autowired private JobRepository jobRepository;
    @Autowired private JobAttemptRepository attemptRepository;
    @Autowired private OutboxEntryRepository outboxRepository;
    @Autowired private AnvilQueue queue;
    @Autowired private OutboxRelay outboxRelay;
    @Autowired private WorkerRunner workerRunner;
    @Autowired private WorkerRepository workerRepository;
    @Autowired private WorkerWatchdog workerWatchdog;
    @Autowired private JobHandlerRegistry handlerRegistry;
    @Autowired private MockMvc mockMvc;

    private UUID testUserId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        jdbc.execute("INSERT INTO users (id, email, password_hash, role, is_active) VALUES ('"
                + testUserId + "', 'test" + System.nanoTime() + "@test.com', 'hash', 'USER', true)");
    }

    @Test
    @Order(1)
    void jobCreation_writesOutboxEntry() {
        CreateJobRequest req = new CreateJobRequest("REPORT_GENERATION", "{}", JobPriority.MEDIUM, null, null);
        var response = jobService.createJob(req, testUserId, null);

        List<OutboxEntry> retryable = outboxRepository.findRetryableEntries();
        boolean found = retryable.stream().anyMatch(e -> e.getJobId().equals(response.id()));
        assertTrue(found, "Outbox entry should exist for created job");
    }

    @Test
    @Order(2)
    void outboxRelay_pushesToRedisQueue() {
        List<OutboxEntry> retryable = outboxRepository.findRetryableEntries();
        assertFalse(retryable.isEmpty(), "Should have retryable outbox entries");

        outboxRelay.relay();

        List<OutboxEntry> stillRetryable = outboxRepository.findRetryableEntries();
        assertTrue(stillRetryable.isEmpty(), "All entries should be relayed");
    }

    @Test
    @Order(3)
    void handlerRegistry_discoversAllHandlers() {
        assertTrue(handlerRegistry.hasHandler("REPORT_GENERATION"));
        assertTrue(handlerRegistry.hasHandler("IMAGE_PROCESSING"));
        assertTrue(handlerRegistry.hasHandler("CSV_IMPORT"));
        assertTrue(handlerRegistry.hasHandler("EMAIL_CAMPAIGN"));
        assertTrue(handlerRegistry.hasHandler("AI_CONTENT_GENERATION"));
        assertTrue(handlerRegistry.hasHandler("FILE_COMPRESSION"));
        assertFalse(handlerRegistry.hasHandler("NONEXISTENT"));
    }

    @Test
    @Order(4)
    void workerRunner_dispatchesToHandler() {
        for (JobPriority p : JobPriority.values()) {
            while (queue.claim("drain-worker", 1).isPresent()) {}
        }

        Job job = new Job(testUserId, "AI_CONTENT_GENERATION", "{}", JobStatus.QUEUED);
        job.setMaxRetries(1);
        jobRepository.saveAndFlush(job);

        queue.enqueue(job.getId(), JobPriority.MEDIUM);

        workerRunner.poll();

        Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            Job completed = jobRepository.findById(job.getId()).orElseThrow();
            assertEquals(JobStatus.COMPLETED, completed.getStatus());
            assertNotNull(completed.getResult());
            assertTrue(completed.getResult().contains("generatedText"));
            assertEquals(100, completed.getProgressPct().intValue());
        });
    }

    @Test
    @Order(5)
    void workerRunner_createsJobAttempt() {
        for (JobPriority p : JobPriority.values()) {
            while (queue.claim("drain-worker", 1).isPresent()) {}
        }

        Job job = new Job(testUserId, "AI_CONTENT_GENERATION", "{}", JobStatus.QUEUED);
        job.setMaxRetries(1);
        jobRepository.saveAndFlush(job);

        queue.enqueue(job.getId(), JobPriority.MEDIUM);

        workerRunner.poll();

        Awaitility.await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            Job completed = jobRepository.findById(job.getId()).orElseThrow();
            assertEquals(JobStatus.COMPLETED, completed.getStatus());
        });

        List<JobAttempt> attempts = attemptRepository.findAll().stream()
                .filter(a -> a.getJob().getId().equals(job.getId()))
                .toList();
        assertFalse(attempts.isEmpty(), "JobAttempt should be created");
        JobAttempt attempt = attempts.get(0);
        assertEquals(JobStatus.COMPLETED, attempt.getStatus());
        assertNotNull(attempt.getStartedAt());
        assertNotNull(attempt.getEndedAt());
        assertEquals(1, attempt.getAttemptNumber());
    }

    @Test
    @Order(6)
    void multipleWorkers_distributeJobs() {
        for (int i = 0; i < 10; i++) {
            Job job = new Job(testUserId, "TEST", "{}", JobStatus.QUEUED);
            jobRepository.saveAndFlush(job);
            queue.enqueue(job.getId(), JobPriority.MEDIUM);
        }

        AtomicInteger worker1Count = new AtomicInteger(0);
        AtomicInteger worker2Count = new AtomicInteger(0);

        for (int i = 0; i < 10; i++) {
            String worker = (i % 2 == 0) ? "test-worker-1" : "test-worker-2";
            if (queue.claim(worker, 30).isPresent()) {
                if ("test-worker-1".equals(worker)) worker1Count.incrementAndGet();
                else worker2Count.incrementAndGet();
            }
        }

        int total = worker1Count.get() + worker2Count.get();
        assertEquals(10, total, "All 10 jobs should be claimed");
        assertTrue(worker1Count.get() > 0 && worker2Count.get() > 0,
                "Jobs should be distributed across workers");
    }

    @Test
    @Order(7)
    void priorityOrdering_HIGH_before_LOW() {
        Job lowJob = new Job(testUserId, "TEST", "{}", JobStatus.QUEUED);
        jobRepository.saveAndFlush(lowJob);
        Job highJob = new Job(testUserId, "TEST", "{}", JobStatus.QUEUED);
        jobRepository.saveAndFlush(highJob);

        queue.enqueue(lowJob.getId(), JobPriority.LOW);
        queue.enqueue(highJob.getId(), JobPriority.HIGH);

        AnvilQueue.QueueClaim claim = queue.claim("test-worker-1", 30).orElseThrow();
        assertEquals(highJob.getId(), claim.jobId(), "HIGH priority should be claimed first");
    }

    @Test
    @Order(8)
    void agingSafeguard_promotesLowPriorityJobs() throws Exception {
        Job lowJob = new Job(testUserId, "TEST", "{}", JobStatus.QUEUED);
        jobRepository.saveAndFlush(lowJob);
        queue.enqueue(lowJob.getId(), JobPriority.LOW);

        Thread.sleep(150);

        if (queue instanceof RedisQueue redisQueue) {
            redisQueue.promoteAgedJobs(100);
        }

        long mediumSize = queue.size(JobPriority.MEDIUM);
        assertTrue(mediumSize > 0, "Aged LOW job should have been promoted to MEDIUM");
    }

    @Test
    @Order(9)
    void watchdog_marksStaleWorker() {
        jdbc.execute("INSERT INTO workers (id, hostname, status, last_heartbeat_at, started_at) VALUES ("
                + "'a0000000-0000-0000-0000-000000000001', 'stale-worker', 'HEALTHY', "
                + "'" + Instant.now().minusMillis(10000) + "', '" + Instant.now() + "')");

        workerWatchdog.checkStaleWorkers();

        String status = jdbc.queryForObject(
                "SELECT status::text FROM workers WHERE hostname = 'stale-worker'", String.class);
        assertEquals("UNHEALTHY", status, "Stale worker should be marked UNHEALTHY");
    }

    @Test
    @Order(10)
    void workerHeartbeat_updatesTimestamp() {
        workerRunner.heartbeat();

        Worker worker = workerRepository.findByHostname("f0000000-0000-0000-0000-000000000001").orElseThrow();
        assertNotNull(worker.getLastHeartbeatAt());
        assertTrue(worker.getLastHeartbeatAt().toEpochMilli() > System.currentTimeMillis() - 5000);
    }

    @Test
    @Order(11)
    void outboxRelay_retriesFailedEntry() {
        Job job = new Job(testUserId, "TEST", "{}", JobStatus.QUEUED);
        jobRepository.saveAndFlush(job);

        OutboxEntry entry = new OutboxEntry(job.getId(), JobPriority.MEDIUM);
        entry.setStatus(OutboxStatus.FAILED);
        entry.setRetryCount(1);
        entry.setNextRetryAt(Instant.now().minusSeconds(1));
        outboxRepository.save(entry);

        List<OutboxEntry> retryable = outboxRepository.findRetryableEntries();
        assertTrue(retryable.stream().anyMatch(e -> e.getJobId().equals(job.getId())),
                "FAILED entry with past next_retry_at should be retryable");

        outboxRelay.relay();

        OutboxEntry updated = outboxRepository.findAll().stream()
                .filter(e -> e.getJobId().equals(job.getId())).findFirst().orElseThrow();
        assertEquals(OutboxStatus.RELAYED, updated.getStatus());
        assertEquals(1, updated.getRetryCount(), "Retry count should be preserved after successful relay");
    }

    @Test
    @Order(12)
    void workerRunner_handlerNotFound_failsPermanently() {
        for (JobPriority p : JobPriority.values()) {
            while (queue.claim("drain-worker", 1).isPresent()) {}
        }

        Job job = new Job(testUserId, "NONEXISTENT_TYPE", "{}", JobStatus.QUEUED);
        job.setMaxRetries(1);
        jobRepository.saveAndFlush(job);

        queue.enqueue(job.getId(), JobPriority.MEDIUM);

        workerRunner.poll();

        Job failed = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.FAILED_PERMANENTLY, failed.getStatus());
        assertNotNull(failed.getErrorMessage());
        assertTrue(failed.getErrorMessage().contains("No handler"));
    }

    @Test
    @Order(13)
    void watchdog_timeout_failsRunningJob() {
        Job job = new Job(testUserId, "TEST", "{}", JobStatus.QUEUED);
        job.setMaxRetries(2);
        job.setStartedAt(Instant.now().minusSeconds(9999));
        job.setTimeoutAt(Instant.now().minusSeconds(1));
        job.setAttemptCount(1);
        jobRepository.saveAndFlush(job);

        jdbc.execute("UPDATE jobs SET status = 'RUNNING', started_at = '"
                + Instant.now().minusSeconds(9999) + "', timeout_at = '"
                + Instant.now().minusSeconds(1) + "' WHERE id = '" + job.getId() + "'");

        workerWatchdog.checkTimedOutJobs();

        Job updated = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.RETRYING, updated.getStatus(),
                "Timed out job should be RETRYING (attempt 1 < max 2)");
    }

    @Test
    @Order(14)
    void watchdog_timeout_failsPermanentlyAfterMaxRetries() {
        Job job = new Job(testUserId, "TEST", "{}", JobStatus.QUEUED);
        job.setMaxRetries(1);
        job.setAttemptCount(1);
        jobRepository.saveAndFlush(job);

        jdbc.execute("UPDATE jobs SET status = 'RUNNING', started_at = '"
                + Instant.now().minusSeconds(9999) + "', timeout_at = '"
                + Instant.now().minusSeconds(1) + "', attempt_count = 1, max_retries = 1 WHERE id = '"
                + job.getId() + "'");

        workerWatchdog.checkTimedOutJobs();

        Job updated = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.FAILED_PERMANENTLY, updated.getStatus(),
                "Timed out job with exhausted retries should be FAILED_PERMANENTLY");
    }

    @Test
    @Order(15)
    void csvImport_cancelDuringExecution_reachesCancelled() throws Exception {
        for (JobPriority p : JobPriority.values()) {
            while (queue.claim("drain-worker", 1).isPresent()) {}
        }

        String payload = "{\"totalRows\": 10000}";
        Job job = new Job(testUserId, "CSV_IMPORT", payload, JobStatus.QUEUED);
        job.setMaxRetries(2);
        jobRepository.saveAndFlush(job);

        queue.enqueue(job.getId(), JobPriority.MEDIUM);

        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch cancelDone = new CountDownLatch(1);

        Thread workerThread = new Thread(() -> {
            handlerStarted.countDown();
            workerRunner.poll();
            cancelDone.countDown();
        });
        workerThread.setDaemon(true);
        workerThread.start();

        assertTrue(handlerStarted.await(5, TimeUnit.SECONDS), "Handler thread should start");

        Thread.sleep(200);

        mockMvc.perform(post("/api/v1/jobs/" + job.getId() + "/cancel")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(testUserId.toString()))))
                .andExpect(status().isOk());

        assertTrue(cancelDone.await(10, TimeUnit.SECONDS), "Worker thread should finish");

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            Job cancelled = jobRepository.findById(job.getId()).orElseThrow();
            assertEquals(JobStatus.CANCELLED, cancelled.getStatus(),
                    "CSV_IMPORT job should reach CANCELLED after cancel during execution");
        });
    }

    @Test
    @Order(16)
    void reclaimOrphanedJobs_requeuesRunningJobNotInClaimedSet() {
        for (JobPriority p : JobPriority.values()) {
            while (queue.claim("drain-worker", 1).isPresent()) {}
        }

        Job job = new Job(testUserId, "AI_CONTENT_GENERATION", "{}", JobStatus.QUEUED);
        job.setMaxRetries(2);
        jobRepository.saveAndFlush(job);

        queue.enqueue(job.getId(), JobPriority.MEDIUM);

        jdbc.execute("UPDATE jobs SET status = 'RUNNING', started_at = '" + Instant.now()
                + "', attempt_count = 1 WHERE id = '" + job.getId() + "'");

        workerWatchdog.reclaimOrphanedJobs();

        Job updated = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.RETRYING, updated.getStatus(),
                "Orphaned RUNNING job should be RETRYING (attempt 1 < max 2)");
    }

    @Test
    @Order(17)
    void reclaimOrphanedJobs_doesNotTouchClaimedJob() {
        for (JobPriority p : JobPriority.values()) {
            while (queue.claim("drain-worker", 1).isPresent()) {}
        }

        Job job = new Job(testUserId, "AI_CONTENT_GENERATION", "{}", JobStatus.QUEUED);
        job.setMaxRetries(2);
        jobRepository.saveAndFlush(job);

        queue.enqueue(job.getId(), JobPriority.MEDIUM);
        queue.claim("active-worker", 60);

        jdbc.execute("UPDATE jobs SET status = 'RUNNING', started_at = '" + Instant.now()
                + "', attempt_count = 1 WHERE id = '" + job.getId() + "'");

        workerWatchdog.reclaimOrphanedJobs();

        Job updated = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.RUNNING, updated.getStatus(),
                "Claimed RUNNING job should NOT be reclaimed");
    }
}
