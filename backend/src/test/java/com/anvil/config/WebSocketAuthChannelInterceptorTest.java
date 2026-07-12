package com.anvil.config;

import com.anvil.auth.JwtProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthChannelInterceptorTest {

    @Mock
    private JwtProvider jwtProvider;

    private WebSocketAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthChannelInterceptor(jwtProvider);
    }

    @Test
    void authenticateConnect_validToken_setsUser() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer valid-token");

        when(jwtProvider.validateToken("valid-token")).thenReturn(true);
        when(jwtProvider.getUserIdFromToken("valid-token")).thenReturn("user-123");
        when(jwtProvider.getRoleFromToken("valid-token")).thenReturn("USER");

        assertDoesNotThrow(() -> interceptor.authenticateConnect(accessor));
        verify(jwtProvider).validateToken("valid-token");
        verify(jwtProvider).getUserIdFromToken("valid-token");
        verify(jwtProvider).getRoleFromToken("valid-token");
        assertNotNull(accessor.getUser());
        assertEquals("user-123", accessor.getUser().getName());
    }

    @Test
    void authenticateConnect_noAuthHeader_throwsMessagingException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);

        MessagingException ex = assertThrows(MessagingException.class,
                () -> interceptor.authenticateConnect(accessor));
        assertEquals("Authentication required", ex.getMessage());
        verifyNoInteractions(jwtProvider);
    }

    @Test
    void authenticateConnect_invalidToken_throwsMessagingException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer bad-token");

        when(jwtProvider.validateToken("bad-token")).thenReturn(false);

        MessagingException ex = assertThrows(MessagingException.class,
                () -> interceptor.authenticateConnect(accessor));
        assertEquals("Invalid or expired token", ex.getMessage());
        verify(jwtProvider).validateToken("bad-token");
    }

    @Test
    void authenticateConnect_tokenException_wrapsInMessagingException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer err-token");

        when(jwtProvider.validateToken("err-token")).thenThrow(new RuntimeException("JWT error"));

        MessagingException ex = assertThrows(MessagingException.class,
                () -> interceptor.authenticateConnect(accessor));
        assertTrue(ex.getMessage().contains("Authentication failed"));
    }

    @Test
    void authenticateConnect_nonBearerScheme_throwsMessagingException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Basic dXNlcjpwYXNz");

        MessagingException ex = assertThrows(MessagingException.class,
                () -> interceptor.authenticateConnect(accessor));
        assertEquals("Authentication required", ex.getMessage());
        verifyNoInteractions(jwtProvider);
    }

    @Test
    void authenticateConnect_emptyBearerToken_throwsMessagingException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer ");

        when(jwtProvider.validateToken("")).thenReturn(false);

        MessagingException ex = assertThrows(MessagingException.class,
                () -> interceptor.authenticateConnect(accessor));
        assertEquals("Invalid or expired token", ex.getMessage());
    }

    @Test
    void authenticateConnect_setsRoleAuthority() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer admin-token");

        when(jwtProvider.validateToken("admin-token")).thenReturn(true);
        when(jwtProvider.getUserIdFromToken("admin-token")).thenReturn("admin-123");
        when(jwtProvider.getRoleFromToken("admin-token")).thenReturn("ADMIN");

        assertDoesNotThrow(() -> interceptor.authenticateConnect(accessor));
        assertNotNull(accessor.getUser());
        var auth = (org.springframework.security.authentication.UsernamePasswordAuthenticationToken) accessor.getUser();
        assertTrue(auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
    }

    @Test
    void authorizeSubscribe_ownTopic_succeeds() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/user/user-42/notifications");
        accessor.setUser(new UsernamePasswordAuthenticationToken("user-42", null));

        assertDoesNotThrow(() -> interceptor.authorizeSubscribe(accessor));
    }

    @Test
    void authorizeSubscribe_otherUserTopic_throwsMessagingException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/user/user-99/notifications");
        accessor.setUser(new UsernamePasswordAuthenticationToken("user-42", null));

        MessagingException ex = assertThrows(MessagingException.class,
                () -> interceptor.authorizeSubscribe(accessor));
        assertTrue(ex.getMessage().contains("Access denied"));
    }

    @Test
    void authorizeSubscribe_noPrincipal_throwsMessagingException() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/user/user-42/notifications");

        MessagingException ex = assertThrows(MessagingException.class,
                () -> interceptor.authorizeSubscribe(accessor));
        assertEquals("Not authenticated", ex.getMessage());
    }

    @Test
    void authorizeSubscribe_nonUserTopic_ignored() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/job/some-job-id");
        accessor.setUser(new UsernamePasswordAuthenticationToken("user-42", null));

        assertDoesNotThrow(() -> interceptor.authorizeSubscribe(accessor));
    }
}
