package com.anvil.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtProviderTest {

    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(
                "YW52aWwtZGV2LXNlY3JldC1rZXktZm9yLWp3dC10b2tlbi1nZW5lcmF0aW9uLTIwMjU=",
                900000L,
                604800000L);
    }

    private User createUser(String email, Role role) throws Exception {
        User user = new User(email, "hash", role);
        Field idField = User.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(user, UUID.randomUUID());
        return user;
    }

    @Test
    void generateAccessToken_returnsValidToken() throws Exception {
        User user = createUser("test@example.com", Role.USER);

        String token = jwtProvider.generateAccessToken(user);

        assertNotNull(token);
        assertTrue(jwtProvider.validateToken(token));
    }

    @Test
    void generateAccessToken_containsCorrectClaims() throws Exception {
        User user = createUser("test@example.com", Role.ADMIN);

        String token = jwtProvider.generateAccessToken(user);
        Claims claims = jwtProvider.parseToken(token);

        assertEquals("test@example.com", claims.get("email", String.class));
        assertEquals("ADMIN", claims.get("role", String.class));
    }

    @Test
    void getUserIdFromToken_returnsUserId() throws Exception {
        User user = createUser("test@example.com", Role.USER);

        String token = jwtProvider.generateAccessToken(user);
        String userId = jwtProvider.getUserIdFromToken(token);

        assertEquals(user.getId().toString(), userId);
    }

    @Test
    void validateToken_invalidToken_returnsFalse() {
        assertFalse(jwtProvider.validateToken("invalid.token.here"));
    }

    @Test
    void validateToken_tamperedToken_returnsFalse() throws Exception {
        User user = createUser("test@example.com", Role.USER);
        String token = jwtProvider.generateAccessToken(user);
        String tampered = token.substring(0, token.length() - 5) + "XXXXX";

        assertFalse(jwtProvider.validateToken(tampered));
    }

    @Test
    void generateRefreshToken_returnsNonEmptyToken() {
        String token = jwtProvider.generateRefreshToken();
        assertNotNull(token);
        assertFalse(token.isEmpty());
    }
}
