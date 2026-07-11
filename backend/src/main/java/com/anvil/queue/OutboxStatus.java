package com.anvil.queue;

public enum OutboxStatus {
    PENDING,
    RELAYED,
    FAILED
}
