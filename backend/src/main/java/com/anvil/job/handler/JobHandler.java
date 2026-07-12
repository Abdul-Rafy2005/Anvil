package com.anvil.job.handler;

import java.time.Duration;

/**
 * Contract for job type implementations.
 *
 * <p><b>At-least-once execution:</b> Handlers may be executed more than once for the
 * same attempt after a worker crash and recovery. Implementations must either be
 * naturally idempotent (safe to re-execute) or implement their own deduplication
 * (e.g. idempotency keys, conditional writes). Handlers that perform real side effects
 * (sending emails, writing to external APIs, mutating shared state) are NOT idempotent
 * by default and must document this limitation.
 */
public interface JobHandler<TPayload, TResult> {

    String jobType();

    int defaultMaxRetries();

    Duration defaultTimeout();

    TResult execute(TPayload payload, JobExecutionContext ctx) throws Exception;
}
