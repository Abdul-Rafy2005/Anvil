package com.anvil.job.handler;

import java.time.Duration;

public interface JobHandler<TPayload, TResult> {

    String jobType();

    int defaultMaxRetries();

    Duration defaultTimeout();

    TResult execute(TPayload payload, JobExecutionContext ctx) throws Exception;
}
