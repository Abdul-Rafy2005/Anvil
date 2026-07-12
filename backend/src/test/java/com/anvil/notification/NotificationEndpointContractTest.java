package com.anvil.notification;

import com.anvil.api.dto.*;
import com.anvil.api.GlobalExceptionHandler;
import com.anvil.auth.*;
import com.anvil.job.domain.*;
import com.anvil.job.service.JobNotFoundException;
import com.anvil.job.service.JobService;
import com.anvil.notification.dto.NotificationListResponse;
import com.anvil.notification.dto.NotificationResponse;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = NotificationController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, GlobalExceptionHandler.class})
class NotificationEndpointContractTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private NotificationService notificationService;

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
    void getNotifications_returnsPaginatedList() throws Exception {
        NotificationResponse notif = new NotificationResponse(
                UUID.randomUUID(), jobId, "JOB_COMPLETED", "Job completed", false, Instant.now());
        NotificationListResponse response = new NotificationListResponse(
                List.of(notif), 1, 0, 20, 1, 1);
        when(notificationService.getNotifications(eq(userId), eq(0), eq(20))).thenReturn(response);

        mockMvc.perform(get("/api/v1/notifications")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].jobId").value(jobId.toString()))
                .andExpect(jsonPath("$.content[0].type").value("JOB_COMPLETED"))
                .andExpect(jsonPath("$.content[0].read").value(false))
                .andExpect(jsonPath("$.unreadCount").value(1));
    }

    @Test
    void getNotifications_withPagination() throws Exception {
        NotificationListResponse response = new NotificationListResponse(
                List.of(), 0, 1, 10, 0, 0);
        when(notificationService.getNotifications(eq(userId), eq(1), eq(10))).thenReturn(response);

        mockMvc.perform(get("/api/v1/notifications")
                        .param("page", "1")
                        .param("size", "10")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    void getNotifications_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void getUnreadCount_returnsCount() throws Exception {
        when(notificationService.getUnreadCount(userId)).thenReturn(5L);

        mockMvc.perform(get("/api/v1/notifications/unread-count")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(5));
    }

    @Test
    void markAsRead_returnsSuccess() throws Exception {
        UUID notifId = UUID.randomUUID();
        when(notificationService.markAsRead(notifId, userId)).thenReturn(true);

        mockMvc.perform(post("/api/v1/notifications/" + notifId + "/read")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Notification marked as read"));
    }

    @Test
    void markAsRead_notFound_returnsAlreadyRead() throws Exception {
        UUID notifId = UUID.randomUUID();
        when(notificationService.markAsRead(notifId, userId)).thenReturn(false);

        mockMvc.perform(post("/api/v1/notifications/" + notifId + "/read")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Notification already read or not found"));
    }

    @Test
    void markAsRead_unauthenticated_returns401() throws Exception {
        UUID notifId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/notifications/" + notifId + "/read"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void markAsRead_asDifferentUser_noEffect() throws Exception {
        UUID notifId = UUID.randomUUID();
        when(notificationService.markAsRead(notifId, userId)).thenReturn(false);

        mockMvc.perform(post("/api/v1/notifications/" + notifId + "/read")
                        .with(SecurityMockMvcRequestPostProcessors.jwt()
                                .authorities(new SimpleGrantedAuthority("ROLE_USER"))
                                .jwt(j -> j.subject(userId.toString()))))
                .andExpect(status().isOk());
    }
}
