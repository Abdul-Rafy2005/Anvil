package com.anvil.job.handler;

public class UnknownJobTypeException extends RuntimeException {
    public UnknownJobTypeException(String jobType) {
        super("No handler registered for job type: " + jobType);
    }
}
