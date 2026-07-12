package com.anvil.api;

import com.anvil.admin.AdminWorkerService;
import com.anvil.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminWorkerController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
class AdminWorkerEndpointContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminWorkerService adminWorkerService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private LoginRateLimiter loginRateLimiter;

    @Test
    void listWorkers_asAdmin_returns200() throws Exception {
        Map<String, Object> worker = Map.of(
                "id", UUID.randomUUID().toString(),
                "hostname", "worker-1",
                "status", "HEALTHY",
                "heartbeatAgeSeconds", 5);
        when(adminWorkerService.listWorkers()).thenReturn(List.of(worker));

        mockMvc.perform(get("/api/v1/admin/workers")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].hostname").value("worker-1"));
    }

    @Test
    void listWorkers_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/workers")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void pauseWorker_asAdmin_returns200() throws Exception {
        UUID workerId = UUID.randomUUID();
        Map<String, Object> worker = Map.of(
                "id", workerId.toString(),
                "hostname", "worker-1",
                "status", "PAUSED");
        when(adminWorkerService.pauseWorker(any(UUID.class), any(UUID.class))).thenReturn(worker);

        mockMvc.perform(post("/api/v1/admin/workers/" + workerId + "/pause")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    @Test
    void pauseWorker_asNonAdmin_returns403() throws Exception {
        UUID workerId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/admin/workers/" + workerId + "/pause")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void resumeWorker_asAdmin_returns200() throws Exception {
        UUID workerId = UUID.randomUUID();
        Map<String, Object> worker = Map.of(
                "id", workerId.toString(),
                "hostname", "worker-1",
                "status", "HEALTHY");
        when(adminWorkerService.resumeWorker(any(UUID.class), any(UUID.class))).thenReturn(worker);

        mockMvc.perform(post("/api/v1/admin/workers/" + workerId + "/resume")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEALTHY"));
    }

    @Test
    void resumeWorker_asNonAdmin_returns403() throws Exception {
        UUID workerId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/admin/workers/" + workerId + "/resume")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void restartWorker_asAdmin_returns200() throws Exception {
        UUID workerId = UUID.randomUUID();
        Map<String, Object> worker = Map.of(
                "id", workerId.toString(),
                "hostname", "worker-1",
                "status", "HEALTHY");
        when(adminWorkerService.restartWorker(any(UUID.class), any(UUID.class))).thenReturn(worker);

        mockMvc.perform(post("/api/v1/admin/workers/" + workerId + "/restart")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("HEALTHY"));
    }

    @Test
    void restartWorker_asNonAdmin_returns403() throws Exception {
        UUID workerId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/admin/workers/" + workerId + "/restart")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }
}
