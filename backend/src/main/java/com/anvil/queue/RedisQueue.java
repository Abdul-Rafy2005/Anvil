package com.anvil.queue;

import com.anvil.job.domain.JobPriority;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
public class RedisQueue implements AnvilQueue {

    private static final Logger log = LoggerFactory.getLogger(RedisQueue.class);

    private static final String QUEUE_PREFIX = "anvil:queue:";
    private static final String CLAIMED_SET = "anvil:queue:claimed";

    private final StringRedisTemplate redis;

    public RedisQueue(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void enqueue(UUID jobId, JobPriority priority) {
        String key = queueKey(priority);
        double score = (double) Instant.now().toEpochMilli();
        redis.opsForZSet().add(key, jobId.toString(), score);
        log.debug("Enqueued job={} priority={}", jobId, priority);
    }

    @Override
    public Optional<QueueClaim> claim(String workerId, int visibilityTimeoutSeconds) {
        for (JobPriority priority : JobPriority.values()) {
            String key = queueKey(priority);
            Set<String> candidates = redis.opsForZSet().range(key, 0, 0);
            if (candidates != null && !candidates.isEmpty()) {
                String jobId = candidates.iterator().next();
                Long removed = redis.opsForZSet().remove(key, jobId);
                if (removed != null && removed > 0) {
                    redis.opsForHash().put(CLAIMED_SET, jobId, workerId);
                    redis.expire(CLAIMED_SET, visibilityTimeoutSeconds + 30, TimeUnit.SECONDS);
                    log.debug("Claimed job={} by worker={}", jobId, workerId);
                    return Optional.of(new QueueClaim(UUID.fromString(jobId), workerId));
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public void ack(UUID jobId) {
        redis.opsForHash().delete(CLAIMED_SET, jobId.toString());
        log.debug("Acked job={}", jobId);
    }

    @Override
    public void nack(UUID jobId) {
        redis.opsForHash().delete(CLAIMED_SET, jobId.toString());
        log.debug("Nacked job={}", jobId);
    }

    @Override
    public long size(JobPriority priority) {
        Long size = redis.opsForZSet().zCard(queueKey(priority));
        return size != null ? size : 0;
    }

    public boolean isClaimed(UUID jobId) {
        return redis.opsForHash().hasKey(CLAIMED_SET, jobId.toString());
    }

    public void promoteAgedJobs(long ageThresholdMs) {
        double cutoff = (double) (Instant.now().toEpochMilli() - ageThresholdMs);
        for (JobPriority current : JobPriority.values()) {
            int ordinal = current.ordinal();
            if (ordinal == 0) continue;
            JobPriority promoted = JobPriority.values()[ordinal - 1];
            String fromKey = queueKey(current);
            String toKey = queueKey(promoted);
            Set<String> aged = redis.opsForZSet().rangeByScore(fromKey, 0, cutoff);
            if (aged != null) {
                for (String jobId : aged) {
                    redis.opsForZSet().remove(fromKey, jobId);
                    redis.opsForZSet().add(toKey, jobId, (double) Instant.now().toEpochMilli());
                    log.info("Promoted aged job={} from {} to {}", jobId, current, promoted);
                }
            }
        }
    }

    private String queueKey(JobPriority priority) {
        return QUEUE_PREFIX + priority.name().toLowerCase();
    }
}
