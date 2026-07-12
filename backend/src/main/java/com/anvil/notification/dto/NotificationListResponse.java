package com.anvil.notification.dto;

import java.util.List;

public record NotificationListResponse(
        List<NotificationResponse> content,
        long unreadCount,
        int page,
        int size,
        long totalElements,
        int totalPages
) {}
