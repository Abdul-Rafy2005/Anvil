package com.anvil.auth;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class LoginRateLimiter {

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCKOUT_DURATION_MS = 900_000; // 15 minutes

    private final ConcurrentHashMap<String, AtomicInteger> emailAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> emailLockoutUntil = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AtomicInteger> ipAttempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> ipLockoutUntil = new ConcurrentHashMap<>();

    public void checkRateLimit(String email, String clientIp) {
        checkLockout(email, emailLockoutUntil, "Too many login attempts for this account. Try again later.");
        checkLockout(clientIp, ipLockoutUntil, "Too many login attempts from this IP. Try again later.");
    }

    public void recordFailure(String email, String clientIp) {
        incrementAndMaybeLock(email, emailAttempts, emailLockoutUntil);
        incrementAndMaybeLock(clientIp, ipAttempts, ipLockoutUntil);
    }

    public void reset(String email, String clientIp) {
        emailAttempts.remove(email);
        emailLockoutUntil.remove(email);
        ipAttempts.remove(clientIp);
        ipLockoutUntil.remove(clientIp);
    }

    private void checkLockout(String key, ConcurrentHashMap<String, Long> lockoutMap, String message) {
        Long until = lockoutMap.get(key);
        if (until != null && System.currentTimeMillis() < until) {
            throw new RateLimitExceededException(message, (until - System.currentTimeMillis()) / 1000);
        }
    }

    private void incrementAndMaybeLock(String key,
                                       ConcurrentHashMap<String, AtomicInteger> attemptsMap,
                                       ConcurrentHashMap<String, Long> lockoutMap) {
        AtomicInteger count = attemptsMap.computeIfAbsent(key, k -> new AtomicInteger(0));
        int current = count.incrementAndGet();
        if (current >= MAX_ATTEMPTS) {
            lockoutMap.put(key, System.currentTimeMillis() + LOCKOUT_DURATION_MS);
        }
    }
}
