package com.anvil.job.handler;

import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class AlwaysFailHandler implements JobHandler<String, String> {

    @Override
    public String jobType() { return "ALWAYS_FAIL"; }

    @Override
    public int defaultMaxRetries() { return 3; }

    @Override
    public Duration defaultTimeout() { return Duration.ofSeconds(30); }

    @Override
    public String execute(String payload, JobExecutionContext ctx) throws Exception {
        throw new RuntimeException("Intentional failure for testing");
    }
}
