package com.anvil.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoginRateLimiterTest {

    private LoginRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new LoginRateLimiter();
    }

    @Test
    void checkRateLimit_withinThreshold_noException() {
        assertDoesNotThrow(() -> rateLimiter.checkRateLimit("user@example.com", "192.168.1.1"));
    }

    @Test
    void checkRateLimit_afterMaxEmailAttempts_throws() {
        String email = "user@example.com";
        String ip = "192.168.1.1";
        for (int i = 0; i < 5; i++) {
            rateLimiter.recordFailure(email, ip);
        }

        assertThrows(RateLimitExceededException.class,
                () -> rateLimiter.checkRateLimit(email, ip));
    }

    @Test
    void checkRateLimit_afterMaxIpAttempts_throws() {
        String ip = "10.0.0.1";
        for (int i = 0; i < 5; i++) {
            rateLimiter.recordFailure("victim" + i + "@example.com", ip);
        }

        assertThrows(RateLimitExceededException.class,
                () -> rateLimiter.checkRateLimit("new@example.com", ip));
    }

    @Test
    void reset_allowsRequestsAgain() {
        String email = "user@example.com";
        String ip = "192.168.1.1";
        for (int i = 0; i < 5; i++) {
            rateLimiter.recordFailure(email, ip);
        }

        rateLimiter.reset(email, ip);
        assertDoesNotThrow(() -> rateLimiter.checkRateLimit(email, ip));
    }

    @Test
    void differentEmailsSameIp_shareIpQuota() {
        String ip = "10.0.0.1";
        for (int i = 0; i < 5; i++) {
            rateLimiter.recordFailure("attacker" + i + "@example.com", ip);
        }

        assertThrows(RateLimitExceededException.class,
                () -> rateLimiter.checkRateLimit("next@example.com", ip));
    }

    @Test
    void sameEmailDifferentIps_shareEmailQuota() {
        String email = "target@example.com";
        for (int i = 0; i < 5; i++) {
            rateLimiter.recordFailure(email, "10.0.0." + i);
        }

        assertThrows(RateLimitExceededException.class,
                () -> rateLimiter.checkRateLimit(email, "192.168.1.1"));
    }

    @Test
    void attackerRotatingEmailsFromSameIp_getsThrottled() {
        String attackerIp = "10.99.99.99";

        for (int i = 0; i < 4; i++) {
            rateLimiter.recordFailure("fake" + i + "@other.com", attackerIp);
        }

        assertDoesNotThrow(() -> rateLimiter.checkRateLimit("legit@example.com", attackerIp));

        rateLimiter.recordFailure("one-more@fake.com", attackerIp);

        assertThrows(RateLimitExceededException.class,
                () -> rateLimiter.checkRateLimit("legit@example.com", attackerIp));
    }
}
