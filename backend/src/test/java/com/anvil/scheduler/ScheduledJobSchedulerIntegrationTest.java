package com.anvil.scheduler;

import com.anvil.api.dto.JobResponse;
import com.anvil.job.domain.*;
import com.anvil.job.repository.JobRepository;
import com.anvil.queue.AnvilQueue;
import com.anvil.queue.OutboxEntryRepository;
import com.anvil.queue.OutboxStatus;
import com.anvil.job.service.JobService;
import com.anvil.api.dto.CreateJobRequest;
import org.junit.jupiter.api.*;
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
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties = {
        "worker.poll-interval-ms=999999999",
        "worker.heartbeat-interval-ms=999999999",
        "worker.visibility-timeout-seconds=5",
        "worker.heartbeat-timeout-ms=2000",
        "worker.retry-backoff-seconds=1,2,4,8",
        "watchdog.interval-ms=999999999",
        "queue.relay.interval-ms=999999999",
        "scheduler.retry-interval-ms=999999999",
        "scheduler.scheduled-scan-interval-ms=999999999",
        "spring.flyway.enabled=true"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScheduledJobSchedulerIntegrationTest {

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
    @Autowired private JobService jobService;
    @Autowired private ScheduledJobScheduler scheduler;
    @Autowired private CronSchedulerHelper cronHelper;
    @Autowired private OutboxEntryRepository outboxRepository;

    private UUID testUserId;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        jdbc.execute("INSERT INTO users (id, email, password_hash, role, is_active) VALUES ('"
                + testUserId + "', 'user" + System.nanoTime() + "@test.com', 'hash', 'USER', true)");
    }

    private Job createJobDirectly(JobStatus status, Instant scheduledAt, String cronExpression, Instant nextFireAt) {
        Job job = new Job(testUserId, "ALWAYS_FAIL", "{}", status);
        job.setScheduledAt(scheduledAt);
        job.setCronExpression(cronExpression);
        job.setNextFireAt(nextFireAt);
        return jobRepository.saveAndFlush(job);
    }

    @Test
    @Order(1)
    void scheduledJob_staysCreated_thenFiresToQueued() {
        Job job = createJobDirectly(JobStatus.CREATED,
                Instant.now().plusSeconds(300), null, Instant.now().plusSeconds(300));

        scheduler.processScheduledJobs();

        Job afterScheduler = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.CREATED, afterScheduler.getStatus(),
                "Job should still be CREATED because scheduledAt is in the future");

        job.setScheduledAt(Instant.now().minusSeconds(1));
        job.setNextFireAt(Instant.now().minusSeconds(1));
        jobRepository.saveAndFlush(job);

        scheduler.processScheduledJobs();

        Job afterFire = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, afterFire.getStatus(),
                "After scheduledAt passes, scheduler should transition to QUEUED");
        assertNull(afterFire.getNextFireAt(), "nextFireAt should be cleared for one-shot job");
    }

    @Test
    @Order(2)
    void cronJob_firesAndAdvancesNextFireAt() {
        Instant firstFireAt = Instant.now().minusSeconds(1);
        Job job = createJobDirectly(JobStatus.CREATED,
                null, "0/30 * * * * ?", firstFireAt);

        scheduler.processScheduledJobs();

        Job afterFire = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, afterFire.getStatus(),
                "Cron job should be QUEUED after firing");
        assertNotNull(afterFire.getNextFireAt(),
                "nextFireAt should be advanced for cron job");
        assertTrue(afterFire.getNextFireAt().isAfter(firstFireAt),
                "Next fire time should be after the first fire time");
    }

    @Test
    @Order(3)
    void concurrentSchedulerReplicas_eachJobEnqueuedExactlyOnce() throws Exception {
        int jobCount = 5;
        List<UUID> jobIds = new ArrayList<>();

        for (int i = 0; i < jobCount; i++) {
            Job job = createJobDirectly(JobStatus.CREATED,
                    Instant.now().minusSeconds(1), null, Instant.now().minusSeconds(1));
            jobIds.add(job.getId());
        }

        for (UUID jobId : jobIds) {
            Job job = jobRepository.findById(jobId).orElseThrow();
            assertEquals(JobStatus.CREATED, job.getStatus(), "All jobs should start CREATED");
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);

        Future<?> futureA = executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            for (int i = 0; i < 3; i++) {
                try {
                    scheduler.processScheduledJobs();
                } catch (Exception e) {
                    // Expected due to lock contention - FOR UPDATE SKIP LOCKED handles this
                }
            }
        });

        Future<?> futureB = executor.submit(() -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            for (int i = 0; i < 3; i++) {
                try {
                    scheduler.processScheduledJobs();
                } catch (Exception e) {
                    // Expected due to lock contention
                }
            }
        });

        futureA.get(30, TimeUnit.SECONDS);
        futureB.get(30, TimeUnit.SECONDS);
        executor.shutdown();

        for (UUID jobId : jobIds) {
            long outboxCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM outbox_entries WHERE job_id = '" + jobId + "'",
                    Long.class);
            assertEquals(1L, outboxCount,
                    "Job " + jobId + " should have exactly 1 outbox entry (no double-enqueue)");
        }

        long queuedCount = jobIds.stream()
                .map(id -> jobRepository.findById(id).orElseThrow())
                .filter(j -> j.getStatus() == JobStatus.QUEUED)
                .count();
        assertEquals(jobCount, queuedCount,
                "All " + jobCount + " jobs should be QUEUED exactly once");
    }

    @Test
    @Order(4)
    void scheduledJob_endToEnd_schedulerToQueued() {
        Job job = createJobDirectly(JobStatus.CREATED,
                Instant.now().minusSeconds(1), null, Instant.now().minusSeconds(1));

        scheduler.processScheduledJobs();

        Job afterFire = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, afterFire.getStatus());

        boolean hasOutbox = outboxRepository.existsByJobIdAndStatus(job.getId(), OutboxStatus.PENDING);
        assertTrue(hasOutbox, "Scheduled job should have a PENDING outbox entry after firing");
    }

    @Test
    @Order(5)
    void scheduledJob_cancelledBeforeFire_staysCancelled() {
        Job job = createJobDirectly(JobStatus.CANCELLED,
                Instant.now().plusSeconds(300), null, Instant.now().plusSeconds(300));

        scheduler.processScheduledJobs();

        Job afterScheduler = jobRepository.findById(job.getId()).orElseThrow();
        assertEquals(JobStatus.CANCELLED, afterScheduler.getStatus(),
                "Cancelled scheduled job should not be picked up by scheduler");
    }
}
