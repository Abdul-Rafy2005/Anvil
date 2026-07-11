package com.anvil.job.domain;

public class InvalidJobTransitionException extends RuntimeException {
    public InvalidJobTransitionException(JobStatus from, JobStatus to) {
        super("Invalid job transition: " + from + " -> " + to);
    }
}
