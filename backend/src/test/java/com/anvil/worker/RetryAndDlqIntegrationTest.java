package com.anvil.worker;

import com.anvil.job.domain.*;
import com.anvil.job.handler.JobHandlerRegistry;
import com.anvil.job.repository.DeadLetterEntryRepository;
import com.anvil.job.repository.JobAttemptRepository;
import com.anvil.job.repository.JobRepository;
import com.anvil.queue.AnvilQueue;
import com.anvil.scheduler.RetryScheduler;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest(properties = {
        "worker.id=f0000000-0000-0000-0000-000000000001",
        "worker.poll-interval-ms=999999999",
        "worker.heartbeat-interval-ms=999999999",
        "worker.visibility-timeout-seconds=5",
        "worker.heartbeat-timeout-ms=2000",
        "worker.retry-backoff-seconds=1,2,4,8",
        "watchdog.interval-ms=999999999",
        "queue.relay.interval-ms=999999999",
        "scheduler.retry-interval-ms=999999999",
        "spring.flyway.enabled=true"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RetryAndDlqIntegrationTest {

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
    @Autowired private JobRepository jobRepository;
    @Autowired private JobAttemptRepository attemptRepository;
    @Autowired private DeadLetterEntryRepository dlqRepository;
    @Autowired private AnvilQueue queue;
    @Autowired private WorkerRunner workerRunner;
    @Autowired private WorkerWatchdog workerWatchdog;
    @Autowired private RetryScheduler retryScheduler;
    @Autowired private JobHandlerRegistry handlerRegistry;
    @Autowired private MockMvc mockMvc;

    private UUID testUserId;
    private UUID adminUserId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        adminUserId = UUID.randomUUID();
        jdbc.execute("INSERT INTO users (id, email, password_hash, role, is_active) VALUES ('"
                + testUserId + "', 'user" + System.nanoTime() + "@test.com', 'hash', 'USER', true)");
        jdbc.execute("INSERT INTO users (id, email, password_hash, role, is_active) VALUES ('"
                + adminUserId + "', 'admin" + System.nanoTime() + "@test.com', 'hash', 'ADMIN', true)");
    }

    @Test
    @Order(1)
    void alwaysFailHandler_isRegistered() {
        assertTrue(handlerRegistry.hasHandler("ALWAYS_FAIL"));
    }

    @Test
    @Order(2)
    void alwaysFailHandler_failsAndRetries() {
        Job job = new Job(testUserId, "ALWAYS_FAIL", "{}", JobStatus.QUEUED);
        job.setMaxRetries(3);
        jobRepository.saveAndFlush(job);
        queue.enqueue(job.getId(), JobPriority.MEDIUM);

        workerRunner.poll();

        Job updated = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.RETRYING, updated.getStatus(),
                "First failure should move to RETRYING");
        assertNotNull(updated.getNextRetryAt(), "nextRetryAt should be set");
        assertTrue(updated.getNextRetryAt().isAfter(Instant.now()),
                "nextRetryAt should be in the future");
        assertEquals(1, updated.getAttemptCount());

        List<JobAttempt> attempts = attemptRepository.findAll().stream()
                .filter(a -> a.getJob().getId().equals(job.getId())).toList();
        assertEquals(1, attempts.size());
        assertEquals(JobStatus.FAILED, attempts.get(0).getStatus());
        assertNotNull(attempts.get(0).getError());
    }

    @Test
    @Order(3)
    void retryScheduler_movesRetryingJobsToQueued() {
        Job job = new Job(testUserId, "ALWAYS_FAIL", "{}", JobStatus.RETRYING);
        job.setMaxRetries(3);
        job.setAttemptCount(1);
        job.setNextRetryAt(Instant.now().minusSeconds(1));
        jobRepository.saveAndFlush(job);

        retryScheduler.processRetryingJobs();

        Job updated = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, updated.getStatus(),
                "RetryScheduler should transition RETRYING -> QUEUED");
        assertNull(updated.getNextRetryAt(), "nextRetryAt should be cleared");
    }

    @Test
    @Order(4)
    void exhaustRetries_createsDlqEntry() {
        for (JobPriority p : JobPriority.values()) {
            while (queue.claim("drain-" + p.name(), 1).isPresent()) {}
        }

        Job job = new Job(testUserId, "ALWAYS_FAIL", "{}", JobStatus.QUEUED);
        job.setMaxRetries(4);
        jobRepository.saveAndFlush(job);
        queue.enqueue(job.getId(), JobPriority.MEDIUM);

        workerRunner.poll();
        Job after1 = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.RETRYING, after1.getStatus(), "1st attempt: should be RETRYING");
        assertNotNull(after1.getNextRetryAt());

        Awaitility.await().atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            retryScheduler.processRetryingJobs();
            Job j = jobRepository.findById(job.getId()).orElseThrow();
            assertEquals(JobStatus.QUEUED, j.getStatus(), "Scheduler should transition to QUEUED");
        });
        queue.enqueue(job.getId(), JobPriority.MEDIUM);

        workerRunner.poll();
        Job after2 = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.RETRYING, after2.getStatus(), "2nd attempt: should be RETRYING");

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            retryScheduler.processRetryingJobs();
            Job j = jobRepository.findById(job.getId()).orElseThrow();
            assertEquals(JobStatus.QUEUED, j.getStatus());
        });
        queue.enqueue(job.getId(), JobPriority.MEDIUM);

        workerRunner.poll();
        Job after3 = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.RETRYING, after3.getStatus(), "3rd attempt: should be RETRYING");

        Awaitility.await().atMost(9, TimeUnit.SECONDS).untilAsserted(() -> {
            retryScheduler.processRetryingJobs();
            Job j = jobRepository.findById(job.getId()).orElseThrow();
            assertEquals(JobStatus.QUEUED, j.getStatus());
        });
        queue.enqueue(job.getId(), JobPriority.MEDIUM);

        workerRunner.poll();
        Job after4 = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.FAILED_PERMANENTLY, after4.getStatus(),
                "After max_retries exhausted, job should be FAILED_PERMANENTLY");

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            var dlqEntry = dlqRepository.findByJobId(job.getId());
            assertTrue(dlqEntry.isPresent(), "DLQ entry should exist");
            assertEquals("ALWAYS_FAIL", dlqEntry.get().getJobType());
            assertEquals(testUserId, dlqEntry.get().getUserId());
            assertNotNull(dlqEntry.get().getFailureHistory());
            assertTrue(dlqEntry.get().getFailureHistory().contains("attempts"));
            assertNull(dlqEntry.get().getResolvedAction(), "Should not be resolved yet");
        });

        List<JobAttempt> attempts = attemptRepository.findAll().stream()
                .filter(a -> a.getJob().getId().equals(job.getId()))
                .sorted(Comparator.comparing(JobAttempt::getStartedAt))
                .toList();
        assertEquals(4, attempts.size(), "Should have 4 attempts");

        assertAttemptGap(attempts.get(0), attempts.get(1), 1, "backoff[0]=1s");
        assertAttemptGap(attempts.get(1), attempts.get(2), 2, "backoff[1]=2s");
        assertAttemptGap(attempts.get(2), attempts.get(3), 4, "backoff[2]=4s");
    }

    private void assertAttemptGap(JobAttempt prev, JobAttempt next, long expectedSeconds, String label) {
        assertNotNull(prev.getEndedAt(), label + ": prev endedAt");
        assertNotNull(next.getStartedAt(), label + ": next startedAt");
        long gapMs = next.getStartedAt().toEpochMilli() - prev.getEndedAt().toEpochMilli();
        assertTrue(gapMs >= (expectedSeconds - 1) * 1000 && gapMs <= (expectedSeconds + 5) * 1000,
                String.format("%s: expected ~%ds gap, got %dms", label, expectedSeconds, gapMs));
    }

    @Test
    @Order(5)
    void dlqEndpoint_adminCanList() throws Exception {
        Job job = new Job(testUserId, "ALWAYS_FAIL", "{}", JobStatus.FAILED_PERMANENTLY);
        job.setMaxRetries(1);
        job.setAttemptCount(1);
        job.setCompletedAt(Instant.now());
        jobRepository.saveAndFlush(job);

        DeadLetterEntry entry = new DeadLetterEntry(
                job.getId(), "ALWAYS_FAIL", testUserId, "Max retries exhausted",
                "{\"attempts\":[]}");
        dlqRepository.save(entry);

        mockMvc.perform(get("/api/v1/admin/dlq")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(j -> j.subject(adminUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray())
                .andExpect(jsonPath("$.content[0].jobType").value("ALWAYS_FAIL"));
    }

    @Test
    @Order(6)
    void dlqEndpoint_nonAdminGets403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/dlq")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(testUserId.toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(7)
    void dlqEndpoint_requeueResetsJob() throws Exception {
        Job job = new Job(testUserId, "ALWAYS_FAIL", "{}", JobStatus.FAILED_PERMANENTLY);
        job.setMaxRetries(2);
        job.setAttemptCount(3);
        job.setCompletedAt(Instant.now());
        job.setErrorMessage("Max retries exhausted");
        jobRepository.saveAndFlush(job);

        DeadLetterEntry entry = new DeadLetterEntry(
                job.getId(), "ALWAYS_FAIL", testUserId, "Max retries exhausted",
                "{\"attempts\":[]}");
        dlqRepository.save(entry);

        mockMvc.perform(post("/api/v1/admin/dlq/" + entry.getId() + "/requeue")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(j -> j.subject(adminUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAction").value("REQUEUED"));

        Job requeued = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, requeued.getStatus(),
                "Requeued job should be QUEUED");
        assertEquals(0, requeued.getAttemptCount(),
                "Requeued job should have attemptCount reset to 0");
        assertNull(requeued.getErrorMessage(),
                "Requeued job should have clear error message");
        assertNull(requeued.getNextRetryAt(),
                "Requeued job should have no nextRetryAt");
    }

    @Test
    @Order(8)
    void dlqEndpoint_discardMarksEntry() throws Exception {
        Job job = new Job(testUserId, "ALWAYS_FAIL", "{}", JobStatus.FAILED_PERMANENTLY);
        job.setMaxRetries(1);
        job.setAttemptCount(1);
        job.setCompletedAt(Instant.now());
        jobRepository.saveAndFlush(job);

        DeadLetterEntry entry = new DeadLetterEntry(
                job.getId(), "ALWAYS_FAIL", testUserId, "Max retries exhausted",
                "{\"attempts\":[]}");
        dlqRepository.save(entry);

        mockMvc.perform(post("/api/v1/admin/dlq/" + entry.getId() + "/discard")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(j -> j.subject(adminUserId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resolvedAction").value("DISCARDED"));

        DeadLetterEntry discarded = dlqRepository.findById(entry.getId()).orElseThrow();
        assertEquals("DISCARDED", discarded.getResolvedAction());
        assertEquals(adminUserId, discarded.getResolvedBy());
        assertNotNull(discarded.getResolvedAt());
    }

    @Test
    @Order(9)
    void dlqEndpoint_requeueCreatesOutboxEntry() throws Exception {
        Job job = new Job(testUserId, "ALWAYS_FAIL", "{}", JobStatus.FAILED_PERMANENTLY);
        job.setMaxRetries(2);
        job.setAttemptCount(2);
        job.setCompletedAt(Instant.now());
        jobRepository.saveAndFlush(job);

        DeadLetterEntry entry = new DeadLetterEntry(
                job.getId(), "ALWAYS_FAIL", testUserId, "Max retries exhausted",
                "{\"attempts\":[]}");
        dlqRepository.save(entry);

        mockMvc.perform(post("/api/v1/admin/dlq/" + entry.getId() + "/requeue")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(j -> j.subject(adminUserId.toString()))))
                .andExpect(status().isOk());

        boolean hasOutbox = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_entries WHERE job_id = '" + job.getId()
                        + "' AND status = 'PENDING'", Integer.class) > 0;
        assertTrue(hasOutbox, "Requeue should create a PENDING outbox entry");
    }

    @Test
    @Order(10)
    void watchdog_timeout_exhaustedRetries_createsDlq() {
        Job job = new Job(testUserId, "ALWAYS_FAIL", "{}", JobStatus.QUEUED);
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

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(dlqRepository.existsByJobId(job.getId()),
                    "DLQ entry should be created for timed-out permanently failed job");
        });
    }
}
