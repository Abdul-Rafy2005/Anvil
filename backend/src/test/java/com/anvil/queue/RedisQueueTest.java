package com.anvil.queue;

import com.anvil.job.domain.JobPriority;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class RedisQueueTest {

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    private RedisQueue queue;

    @BeforeEach
    void setUp() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(
                redis.getHost(), redis.getFirstMappedPort());
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        queue = new RedisQueue(template);
        template.execute((org.springframework.data.redis.core.RedisCallback<Void>) conn -> {
            conn.serverCommands().flushAll();
            return null;
        });
    }

    @Test
    void enqueue_and_claim_returnsJobId() {
        UUID jobId = UUID.randomUUID();
        queue.enqueue(jobId, JobPriority.MEDIUM);

        Optional<AnvilQueue.QueueClaim> claim = queue.claim("worker-1", 30);

        assertTrue(claim.isPresent());
        assertEquals(jobId, claim.get().jobId());
        assertEquals("worker-1", claim.get().claimedBy());
    }

    @Test
    void claim_emptyQueue_returnsEmpty() {
        Optional<AnvilQueue.QueueClaim> claim = queue.claim("worker-1", 30);
        assertTrue(claim.isEmpty());
    }

    @Test
    void ack_removesFromClaimed() {
        UUID jobId = UUID.randomUUID();
        queue.enqueue(jobId, JobPriority.MEDIUM);
        queue.claim("worker-1", 30);

        queue.ack(jobId);
        assertFalse(queue.isClaimed(jobId));
    }

    @Test
    void nack_removesFromClaimed() {
        UUID jobId = UUID.randomUUID();
        queue.enqueue(jobId, JobPriority.MEDIUM);
        queue.claim("worker-1", 30);

        queue.nack(jobId);
        assertFalse(queue.isClaimed(jobId));
    }

    @Test
    void priority_ordering_HIGH_before_LOW() {
        UUID lowJob = UUID.randomUUID();
        UUID highJob = UUID.randomUUID();
        queue.enqueue(lowJob, JobPriority.LOW);
        queue.enqueue(highJob, JobPriority.HIGH);

        AnvilQueue.QueueClaim claim = queue.claim("worker-1", 30).orElseThrow();
        assertEquals(highJob, claim.jobId());
    }

    @Test
    void priority_ordering_allTiers() {
        UUID low = UUID.randomUUID();
        UUID med = UUID.randomUUID();
        UUID high = UUID.randomUUID();
        queue.enqueue(low, JobPriority.LOW);
        queue.enqueue(med, JobPriority.MEDIUM);
        queue.enqueue(high, JobPriority.HIGH);

        assertEquals(high, queue.claim("w", 30).orElseThrow().jobId());
        assertEquals(med, queue.claim("w", 30).orElseThrow().jobId());
        assertEquals(low, queue.claim("w", 30).orElseThrow().jobId());
    }

    @Test
    void size_reportsCorrectCount() {
        queue.enqueue(UUID.randomUUID(), JobPriority.HIGH);
        queue.enqueue(UUID.randomUUID(), JobPriority.HIGH);
        queue.enqueue(UUID.randomUUID(), JobPriority.LOW);

        assertEquals(2, queue.size(JobPriority.HIGH));
        assertEquals(1, queue.size(JobPriority.LOW));
        assertEquals(0, queue.size(JobPriority.MEDIUM));
    }

    @Test
    void claim_onlyOnce_underConcurrency() throws Exception {
        UUID jobId = UUID.randomUUID();
        queue.enqueue(jobId, JobPriority.MEDIUM);

        int threadCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicBoolean claimed = new AtomicBoolean(false);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        for (int i = 0; i < threadCount; i++) {
            final int id = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Optional<AnvilQueue.QueueClaim> claim = queue.claim("worker-" + id, 30);
                    if (claim.isPresent()) {
                        assertTrue(claimed.compareAndSet(false, true),
                                "Job was claimed by more than one worker");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();
    }

    @Test
    void FIFO_withinSamePriority() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        queue.enqueue(first, JobPriority.MEDIUM);
        queue.enqueue(second, JobPriority.MEDIUM);

        assertEquals(first, queue.claim("w", 30).orElseThrow().jobId());
        assertEquals(second, queue.claim("w", 30).orElseThrow().jobId());
    }
}
