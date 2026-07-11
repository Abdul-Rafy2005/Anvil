package com.anvil.job.domain;

public enum JobStatus {
    CREATED,
    QUEUED,
    RUNNING,
    FAILED,
    RETRYING,
    FAILED_PERMANENTLY,
    CANCELLING,
    CANCELLED,
    COMPLETED
}
