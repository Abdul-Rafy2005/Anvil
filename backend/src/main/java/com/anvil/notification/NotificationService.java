package com.anvil.notification;

import com.anvil.auth.User;
import com.anvil.auth.UserRepository;
import com.anvil.notification.dto.NotificationListResponse;
import com.anvil.notification.dto.NotificationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final SimpMessagingTemplate messagingTemplate;
    private final EmailNotificationService emailNotificationService;

    public NotificationService(NotificationRepository notificationRepository,
                               UserRepository userRepository,
                               SimpMessagingTemplate messagingTemplate,
                               EmailNotificationService emailNotificationService) {
        this.notificationRepository = notificationRepository;
        this.userRepository = userRepository;
        this.messagingTemplate = messagingTemplate;
        this.emailNotificationService = emailNotificationService;
    }

    @Transactional
    public void createNotification(UUID userId, UUID jobId, NotificationType type, String message) {
        Notification notification = new Notification(userId, jobId, type.name(), message);
        notification = notificationRepository.save(notification);

        NotificationResponse response = NotificationResponse.from(notification);

        messagingTemplate.convertAndSend("/topic/user/" + userId + "/notifications", response);

        log.info("Notification created: userId={} jobId={} type={}", userId, jobId, type);

        userRepository.findById(userId).ifPresent(user -> {
            if (user.isEmailNotificationsEnabled()) {
                emailNotificationService.sendNotificationEmail(user.getEmail(), type, message, jobId);
            }
        });
    }

    @Transactional(readOnly = true)
    public NotificationListResponse getNotifications(UUID userId, int page, int size) {
        Page<Notification> notifications = notificationRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size));
        long unreadCount = notificationRepository.countByUserIdAndReadAtIsNull(userId);

        return new NotificationListResponse(
                notifications.getContent().stream()
                        .map(NotificationResponse::from)
                        .toList(),
                unreadCount,
                notifications.getNumber(),
                notifications.getSize(),
                notifications.getTotalElements(),
                notifications.getTotalPages()
        );
    }

    @Transactional(readOnly = true)
    public long getUnreadCount(UUID userId) {
        return notificationRepository.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public boolean markAsRead(UUID notificationId, UUID userId) {
        int updated = notificationRepository.markAsRead(notificationId, userId, Instant.now());
        return updated > 0;
    }

    public void pushProgressUpdate(UUID jobId, int progressPct, String message, UUID userId) {
        Map<String, Object> payload = Map.of(
                "jobId", jobId.toString(),
                "progressPct", progressPct,
                "message", message,
                "type", "PROGRESS"
        );
        messagingTemplate.convertAndSend("/topic/job/" + jobId, payload);
    }

    public void pushStatusUpdate(UUID jobId, String status, UUID userId, String message) {
        Map<String, Object> payload = Map.of(
                "jobId", jobId.toString(),
                "status", status,
                "message", message,
                "type", "STATUS"
        );
        messagingTemplate.convertAndSend("/topic/job/" + jobId, payload);
    }
}
