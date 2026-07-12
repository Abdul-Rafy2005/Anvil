package com.anvil.notification.dto;

import com.anvil.notification.Notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        UUID jobId,
        String type,
        String message,
        boolean read,
        Instant createdAt
) {
    public static NotificationResponse from(Notification n) {
        return new NotificationResponse(
                n.getId(),
                n.getJobId(),
                n.getType(),
                n.getMessage(),
                n.getReadAt() != null,
                n.getCreatedAt()
        );
    }
}
