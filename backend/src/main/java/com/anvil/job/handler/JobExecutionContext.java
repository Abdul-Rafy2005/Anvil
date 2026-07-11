package com.anvil.job.handler;

import java.util.UUID;

public interface JobExecutionContext {

    UUID jobId();

    int attemptNumber();

    void reportProgress(int pct, String message);

    boolean isCancellationRequested();
}
