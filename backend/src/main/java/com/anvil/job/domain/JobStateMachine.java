package com.anvil.job.domain;

import com.anvil.audit.AuditLogService;
import com.anvil.notification.NotificationService;
import com.anvil.notification.NotificationType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Component
public class JobStateMachine {

    private static final Logger log = LoggerFactory.getLogger(JobStateMachine.class);

    private static final Set<JobStatus> CANCELLABLE_STATES =
            Set.of(JobStatus.CREATED, JobStatus.QUEUED, JobStatus.RUNNING);

    private final AuditLogService auditLogService;
    private final NotificationService notificationService;

    public JobStateMachine(AuditLogService auditLogService, NotificationService notificationService) {
        this.auditLogService = auditLogService;
        this.notificationService = notificationService;
    }

    public void transition(Job job, JobStatus newStatus, UUID actorUserId) {
        transition(job, newStatus, actorUserId, null);
    }

    public void transition(Job job, JobStatus newStatus, UUID actorUserId, String metadata) {
        validateTransition(job.getStatus(), newStatus);
        JobStatus oldStatus = job.getStatus();
        job.setStatus(newStatus);

        if (newStatus == JobStatus.RUNNING && job.getStartedAt() == null) {
            job.setStartedAt(Instant.now());
        }
        if (newStatus == JobStatus.COMPLETED || newStatus == JobStatus.CANCELLED
                || newStatus == JobStatus.FAILED_PERMANENTLY) {
            job.setCompletedAt(Instant.now());
        }

        auditLogService.log(actorUserId, "STATUS_" + oldStatus + "_TO_" + newStatus,
                "Job", job.getId(), metadata);

        notificationService.pushStatusUpdate(job.getId(), newStatus.name(), job.getUserId(),
                "Job status: " + newStatus);

        if (newStatus == JobStatus.COMPLETED || newStatus == JobStatus.FAILED_PERMANENTLY
                || newStatus == JobStatus.CANCELLED) {
            NotificationType type = switch (newStatus) {
                case COMPLETED -> NotificationType.JOB_COMPLETED;
                case FAILED_PERMANENTLY -> NotificationType.JOB_FAILED_PERMANENTLY;
                case CANCELLED -> NotificationType.JOB_CANCELLED;
                default -> null;
            };
            if (type != null) {
                notificationService.createNotification(job.getUserId(), job.getId(), type,
                        "Job " + newStatus.name().toLowerCase().replace('_', ' '));
            }
        }
    }

    public boolean canCancel(JobStatus status) {
        return CANCELLABLE_STATES.contains(status);
    }

    private void validateTransition(JobStatus from, JobStatus to) {
        if (!isLegalTransition(from, to)) {
            throw new InvalidJobTransitionException(from, to);
        }
    }

    boolean isLegalTransition(JobStatus from, JobStatus to) {
        return switch (from) {
            case CREATED -> to == JobStatus.QUEUED || to == JobStatus.CANCELLED;
            case QUEUED -> to == JobStatus.RUNNING || to == JobStatus.CANCELLED;
            case RUNNING -> to == JobStatus.COMPLETED || to == JobStatus.FAILED
                    || to == JobStatus.CANCELLING;
            case FAILED -> to == JobStatus.RETRYING || to == JobStatus.FAILED_PERMANENTLY;
            case RETRYING -> to == JobStatus.QUEUED;
            case CANCELLING -> to == JobStatus.CANCELLED;
            case COMPLETED, CANCELLED, FAILED_PERMANENTLY -> false;
        };
    }
}
