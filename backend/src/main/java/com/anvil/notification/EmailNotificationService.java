package com.anvil.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    @Async
    public void sendNotificationEmail(String toEmail, NotificationType type, String message, UUID jobId) {
        log.info("EMAIL NOTIFICATION [stub] — to={}, type={}, jobId={}, message={}",
                toEmail, type, jobId, message);
    }
}
