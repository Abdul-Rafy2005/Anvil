package com.anvil.config;

import com.anvil.auth.JwtProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.List;
import java.util.regex.Pattern;

@Component
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthChannelInterceptor.class);

    private static final Pattern USER_TOPIC_PATTERN =
            Pattern.compile("^/topic/user/([^/]+)/notifications$");

    private final JwtProvider jwtProvider;

    public WebSocketAuthChannelInterceptor(JwtProvider jwtProvider) {
        this.jwtProvider = jwtProvider;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor != null) {
            if (StompCommand.CONNECT.equals(accessor.getCommand()) && accessor.getUser() == null) {
                authenticateConnect(accessor);
            } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
                authorizeSubscribe(accessor);
            }
        }

        return message;
    }

    void authorizeSubscribe(StompHeaderAccessor accessor) {
        String destination = accessor.getDestination();
        if (destination == null) return;

        var matcher = USER_TOPIC_PATTERN.matcher(destination);
        if (!matcher.matches()) return;

        String targetUserId = matcher.group(1);
        Principal principal = accessor.getUser();
        if (principal == null) {
            log.warn("STOMP SUBSCRIBE to user topic without principal — rejecting: {}", destination);
            throw new MessagingException("Not authenticated");
        }

        String principalName = principal.getName();
        if (!targetUserId.equals(principalName)) {
            log.warn("STOMP SUBSCRIBE denied: user {} attempted to subscribe to {}'s notifications",
                    principalName, targetUserId);
            throw new MessagingException("Access denied: cannot subscribe to another user's notifications");
        }
    }

    void authenticateConnect(StompHeaderAccessor accessor) {
        try {
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            if (authHeader == null || !authHeader.startsWith("Bearer ")) {
                log.warn("STOMP CONNECT without valid Authorization header — rejecting");
                throw new MessagingException("Authentication required");
            }

            String token = authHeader.substring(7);

            if (!jwtProvider.validateToken(token)) {
                log.warn("STOMP CONNECT with invalid JWT token — rejecting");
                throw new MessagingException("Invalid or expired token");
            }

            String userId = jwtProvider.getUserIdFromToken(token);
            String role = jwtProvider.getRoleFromToken(token);

            var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
            var auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
            accessor.setUser(auth);

            log.info("STOMP CONNECT authenticated: userId={}", userId);
        } catch (MessagingException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to authenticate STOMP CONNECT: {}", e.getMessage());
            throw new MessagingException("Authentication failed: " + e.getMessage());
        }
    }
}
