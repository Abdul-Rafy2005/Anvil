package com.anvil.notification;

import com.anvil.auth.User;
import com.anvil.auth.UserRepository;
import com.anvil.notification.dto.NotificationResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class EmailNotificationServiceTest {

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private NotificationRepository notificationRepository;

    @MockitoBean
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private EmailNotificationService emailNotificationService;

    @Test
    void createNotification_emailDisabled_doesNotCallEmailService() {
        UUID userId = UUID.randomUUID();
        User user = new User("disabled@test.com", "hash", com.anvil.auth.Role.USER);
        ReflectionTestUtils.setField(user, "id", userId);
        user.setEmailNotificationsEnabled(false);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        Notification saved = new Notification(userId, UUID.randomUUID(),
                NotificationType.JOB_COMPLETED.name(), "Job done");
        when(notificationRepository.save(any())).thenReturn(saved);

        notificationService.createNotification(userId, UUID.randomUUID(),
                NotificationType.JOB_COMPLETED, "Job done");

        verify(notificationRepository).save(any());
        verify(messagingTemplate).convertAndSend(
                eq("/topic/user/" + userId + "/notifications"), any(NotificationResponse.class));
        verifyNoMoreInteractions(messagingTemplate);
    }

    @Test
    void sendNotificationEmail_annotationPresent() throws NoSuchMethodException {
        var method = EmailNotificationService.class.getMethod(
                "sendNotificationEmail", String.class, NotificationType.class, String.class, UUID.class);
        assertTrue(method.isAnnotationPresent(org.springframework.scheduling.annotation.Async.class),
                "@Async annotation should be present on sendNotificationEmail");
    }
}
