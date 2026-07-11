package com.anvil.api;

import com.anvil.api.dto.*;
import com.anvil.auth.*;
import com.anvil.job.domain.*;
import com.anvil.job.repository.JobRepository;
import com.anvil.job.service.JobNotFoundException;
import com.anvil.job.service.JobService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = JobController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
class JobEndpointContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobService jobService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private LoginRateLimiter loginRateLimiter;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID userId;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        jobId = UUID.randomUUID();
    }

    @Test
    void createJob_validRequest_returns201() throws Exception {
        JobResponse response = new JobResponse(jobId, userId, "REPORT_GENERATION", "{}",
                JobStatus.QUEUED, JobPriority.MEDIUM, null, null, null, null,
                0, 4, null, null, null, Instant.now(), Instant.now(), null, null);
        when(jobService.createJob(any(CreateJobRequest.class), eq(userId), any()))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateJobRequest("REPORT_GENERATION", "{}", null, null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(jobId.toString()))
                .andExpect(jsonPath("$.status").value("QUEUED"))
                .andExpect(jsonPath("$.jobType").value("REPORT_GENERATION"));
    }

    @Test
    void createJob_withIdempotencyKey_returnsSameJob() throws Exception {
        JobResponse response = new JobResponse(jobId, userId, "REPORT_GENERATION", "{}",
                JobStatus.QUEUED, JobPriority.MEDIUM, null, null, null, null,
                0, 4, null, null, null, Instant.now(), Instant.now(), null, null);
        when(jobService.createJob(any(CreateJobRequest.class), eq(userId), eq("idem-123")))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString())))
                        .header("Idempotency-Key", "idem-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateJobRequest("REPORT_GENERATION", "{}", null, null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(jobId.toString()));
    }

    @Test
    void createJob_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateJobRequest("REPORT_GENERATION", "{}", null, null, null))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void listJobs_userSeesOwnJobs() throws Exception {
        UUID otherUserId = UUID.randomUUID();
        JobListResponse ownJob = new JobListResponse(jobId, "REPORT_GENERATION",
                JobStatus.QUEUED, JobPriority.MEDIUM, null, null, null, Instant.now());
        when(jobService.listJobs(eq(userId), eq(false), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(ownJob)));

        mockMvc.perform(get("/api/v1/jobs")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(jobId.toString()));
    }

    @Test
    void listJobs_adminSeesAllJobs() throws Exception {
        JobListResponse job = new JobListResponse(jobId, "REPORT_GENERATION",
                JobStatus.QUEUED, JobPriority.MEDIUM, null, null, null, Instant.now());
        when(jobService.listJobs(eq(userId), eq(true), isNull(), isNull(), any()))
                .thenReturn(new PageImpl<>(List.of(job)));

        mockMvc.perform(get("/api/v1/jobs")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk());
    }

    @Test
    void getJob_found_returns200() throws Exception {
        JobResponse response = new JobResponse(jobId, userId, "REPORT_GENERATION", "{}",
                JobStatus.QUEUED, JobPriority.MEDIUM, null, null, null, null,
                0, 4, null, null, null, Instant.now(), Instant.now(), null, null);
        when(jobService.getJob(eq(jobId), eq(userId), eq(false))).thenReturn(response);

        mockMvc.perform(get("/api/v1/jobs/" + jobId)
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(jobId.toString()));
    }

    @Test
    void getJob_notFound_returns404() throws Exception {
        when(jobService.getJob(eq(jobId), eq(userId), eq(false)))
                .thenThrow(new JobNotFoundException(jobId));

        mockMvc.perform(get("/api/v1/jobs/" + jobId)
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("JOB_NOT_FOUND"));
    }

    @Test
    void cancelJob_queuedJob_returns200() throws Exception {
        JobResponse response = new JobResponse(jobId, userId, "REPORT_GENERATION", "{}",
                JobStatus.CANCELLED, JobPriority.MEDIUM, null, null, null, null,
                0, 4, null, null, null, Instant.now(), Instant.now(), null, null);
        when(jobService.cancelJob(eq(jobId), eq(userId), eq(false))).thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs/" + jobId + "/cancel")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void cancelJob_alreadyCancelled_returns409() throws Exception {
        when(jobService.cancelJob(eq(jobId), eq(userId), eq(false)))
                .thenThrow(new InvalidJobTransitionException(JobStatus.CANCELLED, JobStatus.CANCELLED));

        mockMvc.perform(post("/api/v1/jobs/" + jobId + "/cancel")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_JOB_TRANSITION"));
    }

    @Test
    void createJob_withScheduledAt_returns201_statusCreated() throws Exception {
        Instant futureTime = Instant.now().plusSeconds(300);
        JobResponse response = new JobResponse(jobId, userId, "ALWAYS_FAIL", "{}",
                JobStatus.CREATED, JobPriority.MEDIUM, null, null, null, null,
                0, 4, futureTime, null, futureTime, Instant.now(), Instant.now(), null, null);
        when(jobService.createJob(any(CreateJobRequest.class), eq(userId), any()))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"ALWAYS_FAIL\",\"payload\":\"{}\",\"scheduledAt\":\"" + futureTime + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.scheduledAt").exists())
                .andExpect(jsonPath("$.nextFireAt").exists());
    }

    @Test
    void createJob_withCronExpression_returns201_statusCreated() throws Exception {
        Instant nextFire = Instant.now().plusSeconds(30);
        JobResponse response = new JobResponse(jobId, userId, "ALWAYS_FAIL", "{}",
                JobStatus.CREATED, JobPriority.MEDIUM, null, null, null, null,
                0, 4, null, "0/30 * * * * ?", nextFire, Instant.now(), Instant.now(), null, null);
        when(jobService.createJob(any(CreateJobRequest.class), eq(userId), any()))
                .thenReturn(response);

        mockMvc.perform(post("/api/v1/jobs")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"ALWAYS_FAIL\",\"payload\":\"{}\",\"cronExpression\":\"0/30 * * * * ?\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andExpect(jsonPath("$.cronExpression").value("0/30 * * * * ?"))
                .andExpect(jsonPath("$.nextFireAt").exists());
    }

    @Test
    void createJob_invalidCron_returns400() throws Exception {
        when(jobService.createJob(any(CreateJobRequest.class), eq(userId), any()))
                .thenThrow(new com.anvil.scheduler.InvalidCronExpressionException("bad-cron", "parse error"));

        mockMvc.perform(post("/api/v1/jobs")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"ALWAYS_FAIL\",\"payload\":\"{}\",\"cronExpression\":\"bad-cron\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CRON_EXPRESSION"));
    }

    @Test
    void createJob_bothScheduledAtAndCron_returns400() throws Exception {
        String futureTime = Instant.now().plusSeconds(300).toString();
        mockMvc.perform(post("/api/v1/jobs")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobType\":\"ALWAYS_FAIL\",\"payload\":\"{}\",\"scheduledAt\":\"" + futureTime + "\",\"cronExpression\":\"0/30 * * * * ?\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }
}
