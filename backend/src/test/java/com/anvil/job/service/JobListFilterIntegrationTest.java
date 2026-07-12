package com.anvil.job.service;

import com.anvil.job.domain.Job;
import com.anvil.job.domain.JobPriority;
import com.anvil.job.domain.JobStatus;
import com.anvil.job.repository.JobRepository;
import com.anvil.api.dto.JobListResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

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
class JobListFilterIntegrationTest {

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

    private UUID userA;
    private UUID userB;
    private final PageRequest page = PageRequest.of(0, 100);

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

        userA = UUID.randomUUID();
        userB = UUID.randomUUID();
        jdbc.execute("INSERT INTO users (id, email, password_hash, role, is_active) VALUES ('"
                + userA + "', 'filtera" + System.nanoTime() + "@test.com', 'hash', 'USER', true)");
        jdbc.execute("INSERT INTO users (id, email, password_hash, role, is_active) VALUES ('"
                + userB + "', 'filterb" + System.nanoTime() + "@test.com', 'hash', 'ADMIN', true)");

        saveJob(userA, "CSV_IMPORT", JobStatus.QUEUED);
        saveJob(userA, "CSV_IMPORT", JobStatus.COMPLETED);
        saveJob(userA, "EMAIL_CAMPAIGN", JobStatus.RUNNING);
        saveJob(userB, "CSV_IMPORT", JobStatus.FAILED);
        saveJob(userB, "EMAIL_CAMPAIGN", JobStatus.QUEUED);
    }

    private void saveJob(UUID userId, String jobType, JobStatus status) {
        Job job = new Job(userId, jobType, "{}", status);
        jobRepository.saveAndFlush(job);
    }

    @Test
    void noFilters_adminSeesAllJobs() {
        Page<JobListResponse> result = jobService.listJobs(userB, true, null, null, page);
        assertEquals(5, result.getTotalElements());
    }

    @Test
    void noFilters_userSeesOnlyOwnJobs() {
        Page<JobListResponse> result = jobService.listJobs(userA, false, null, null, page);
        assertEquals(3, result.getTotalElements());
        assertTrue(result.getContent().stream().allMatch(j -> true));
    }

    @Test
    void statusFilter_returnsOnlyMatchingJobs() {
        Page<JobListResponse> result = jobService.listJobs(userB, true, "QUEUED", null, page);
        assertEquals(2, result.getTotalElements());
        assertTrue(result.getContent().stream().allMatch(j -> j.status() == JobStatus.QUEUED));
    }

    @Test
    void jobTypeFilter_returnsOnlyMatchingJobs() {
        Page<JobListResponse> result = jobService.listJobs(userB, true, null, "CSV_IMPORT", page);
        assertEquals(3, result.getTotalElements());
        assertTrue(result.getContent().stream().allMatch(j -> j.jobType().equals("CSV_IMPORT")));
    }

    @Test
    void userFilter_nonAdminSeesOnlyOwnJobs() {
        Page<JobListResponse> result = jobService.listJobs(userA, false, null, null, page);
        assertEquals(3, result.getTotalElements());
    }

    @Test
    void allFilters_returnsIntersection() {
        Page<JobListResponse> result = jobService.listJobs(userA, false, "QUEUED", "CSV_IMPORT", page);
        assertEquals(1, result.getTotalElements());
        JobListResponse job = result.getContent().get(0);
        assertEquals(JobStatus.QUEUED, job.status());
        assertEquals("CSV_IMPORT", job.jobType());
    }

    @Test
    void statusAndJobType_noMatch() {
        Page<JobListResponse> result = jobService.listJobs(userB, true, "COMPLETED", "EMAIL_CAMPAIGN", page);
        assertEquals(0, result.getTotalElements());
    }

    @Test
    void statusFilter_adminSeesAcrossAllUsers() {
        Page<JobListResponse> result = jobService.listJobs(userA, true, "RUNNING", null, page);
        assertEquals(1, result.getTotalElements());
        assertEquals("EMAIL_CAMPAIGN", result.getContent().get(0).jobType());
    }

    @Test
    void invalidStatus_throwsInvalidDataAccessApiUsageException() {
        assertThrows(InvalidDataAccessApiUsageException.class, () ->
                jobService.listJobs(userA, true, "NOT_A_REAL_STATUS", null, page));
    }
}
