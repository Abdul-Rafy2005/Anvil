package com.anvil.api;

import com.anvil.audit.AuditLogQueryService;
import com.anvil.auth.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuditLogController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
class AuditLogEndpointContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuditLogQueryService auditLogQueryService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RefreshTokenRepository refreshTokenRepository;

    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private LoginRateLimiter loginRateLimiter;

    @Test
    void queryAuditLog_asAdmin_returns200() throws Exception {
        Map<String, Object> response = Map.of(
                "content", java.util.List.of(),
                "page", 0,
                "size", 20,
                "totalElements", 0L,
                "totalPages", 0);
        when(auditLogQueryService.query(isNull(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20)))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0));
    }

    @Test
    void queryAuditLog_asNonAdmin_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isForbidden());
    }

    @Test
    void queryAuditLog_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/audit-log"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void queryAuditLog_withFilters_returns200() throws Exception {
        UUID actorId = UUID.randomUUID();
        Map<String, Object> response = Map.of(
                "content", java.util.List.of(),
                "page", 0,
                "size", 10,
                "totalElements", 0L,
                "totalPages", 0);
        when(auditLogQueryService.query(eq(actorId), eq("JOB_CREATED"), isNull(), isNull(), isNull(), eq(0), eq(10)))
                .thenReturn(response);

        mockMvc.perform(get("/api/v1/admin/audit-log")
                        .param("actorUserId", actorId.toString())
                        .param("action", "JOB_CREATED")
                        .param("size", "10")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))
                                .jwt(j -> j.subject(UUID.randomUUID().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(10));
    }
}
