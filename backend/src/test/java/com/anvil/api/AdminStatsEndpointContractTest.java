package com.anvil.api;

import com.anvil.admin.AdminStatsService;
import com.anvil.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AdminStatsController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
class AdminStatsEndpointContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminStatsService adminStatsService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private LoginRateLimiter loginRateLimiter;

    @Test
    void overview_asAdmin_returns200() throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("jobsRunning", 5L);
        stats.put("dlqSize", 2L);
        stats.put("queueSize", 10L);
        when(adminStatsService.getOverview()).thenReturn(stats);

        mockMvc.perform(get("/api/v1/admin/stats/overview")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobsRunning").value(5))
                .andExpect(jsonPath("$.dlqSize").value(2));
    }

    @Test
    void overview_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats/overview")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void overview_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/stats/overview"))
                .andExpect(status().isUnauthorized());
    }
}
