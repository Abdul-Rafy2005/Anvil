package com.anvil.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.*;

class CronSchedulerHelperTest {

    private CronSchedulerHelper cronHelper;

    @BeforeEach
    void setUp() {
        cronHelper = new CronSchedulerHelper();
    }

    @Test
    void validCronExpression_doesNotThrow() {
        assertDoesNotThrow(() -> cronHelper.validate("0 0 12 * * ?"));
    }

    @Test
    void validSpringCron_withSeconds_doesNotThrow() {
        assertDoesNotThrow(() -> cronHelper.validate("0/30 * * * * ?"));
    }

    @Test
    void invalidCronExpression_throwsInvalidCronException() {
        InvalidCronExpressionException ex = assertThrows(
                InvalidCronExpressionException.class,
                () -> cronHelper.validate("not-a-cron")
        );
        assertTrue(ex.getMessage().contains("not-a-cron"));
    }

    @Test
    void invalidCronExpression_tooManyFields_throws() {
        assertThrows(InvalidCronExpressionException.class,
                () -> cronHelper.validate("0 0 12 * * ? extra"));
    }

    @Test
    void getNextFireTime_returnsFutureTime() {
        Instant now = Instant.now();
        Instant next = cronHelper.getNextFireTime("0 0 12 * * ?", now);
        assertNotNull(next);
        assertTrue(next.isAfter(now));
    }

    @Test
    void getNextFireTime_every30Seconds_returnsWithin30s() {
        Instant now = Instant.now();
        Instant next = cronHelper.getNextFireTime("0/30 * * * * ?", now);
        assertNotNull(next);
        long diffSeconds = java.time.Duration.between(now, next).getSeconds();
        assertTrue(diffSeconds >= 0 && diffSeconds <= 30,
                "Next fire time should be within 30 seconds, got " + diffSeconds + "s");
    }

    @Test
    void getNextFireTime_returnsConsistentProgression() {
        Instant now = Instant.now();
        Instant first = cronHelper.getNextFireTime("0/30 * * * * ?", now);
        assertNotNull(first);
        Instant second = cronHelper.getNextFireTime("0/30 * * * * ?", first);
        assertNotNull(second);
        assertTrue(second.isAfter(first),
                "Second fire time should be after first");
        long diffSeconds = java.time.Duration.between(first, second).getSeconds();
        assertEquals(30, diffSeconds, "Consecutive fires should be 30 seconds apart");
    }
}
