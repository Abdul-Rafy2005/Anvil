package com.anvil.job.domain;

import com.anvil.audit.AuditLogService;
import com.anvil.notification.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobStateMachineTest {

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private NotificationService notificationService;

    private JobStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new JobStateMachine(auditLogService, notificationService);
    }

    private Job jobWithStatus(JobStatus status) {
        Job job = new Job(UUID.randomUUID(), "TEST", "{}", status);
        return job;
    }

    @Test
    void created_toQueued_legal() {
        Job job = jobWithStatus(JobStatus.CREATED);
        stateMachine.transition(job, JobStatus.QUEUED, UUID.randomUUID());
        assertEquals(JobStatus.QUEUED, job.getStatus());
        verify(auditLogService).log(any(), eq("STATUS_CREATED_TO_QUEUED"), eq("Job"), eq(job.getId()), any());
    }

    @Test
    void created_toCancelled_legal() {
        Job job = jobWithStatus(JobStatus.CREATED);
        stateMachine.transition(job, JobStatus.CANCELLED, UUID.randomUUID());
        assertEquals(JobStatus.CANCELLED, job.getStatus());
    }

    @Test
    void queued_toRunning_legal() {
        Job job = jobWithStatus(JobStatus.QUEUED);
        stateMachine.transition(job, JobStatus.RUNNING, UUID.randomUUID());
        assertEquals(JobStatus.RUNNING, job.getStatus());
        assertNotNull(job.getStartedAt());
    }

    @Test
    void queued_toCancelled_legal() {
        Job job = jobWithStatus(JobStatus.QUEUED);
        stateMachine.transition(job, JobStatus.CANCELLED, UUID.randomUUID());
        assertEquals(JobStatus.CANCELLED, job.getStatus());
    }

    @Test
    void running_toCompleted_legal() {
        Job job = jobWithStatus(JobStatus.RUNNING);
        stateMachine.transition(job, JobStatus.COMPLETED, UUID.randomUUID());
        assertEquals(JobStatus.COMPLETED, job.getStatus());
        assertNotNull(job.getCompletedAt());
    }

    @Test
    void running_toFailed_legal() {
        Job job = jobWithStatus(JobStatus.RUNNING);
        stateMachine.transition(job, JobStatus.FAILED, UUID.randomUUID());
        assertEquals(JobStatus.FAILED, job.getStatus());
    }

    @Test
    void running_toCancelling_legal() {
        Job job = jobWithStatus(JobStatus.RUNNING);
        stateMachine.transition(job, JobStatus.CANCELLING, UUID.randomUUID());
        assertEquals(JobStatus.CANCELLING, job.getStatus());
    }

    @Test
    void failed_toRetrying_legal() {
        Job job = jobWithStatus(JobStatus.FAILED);
        stateMachine.transition(job, JobStatus.RETRYING, UUID.randomUUID());
        assertEquals(JobStatus.RETRYING, job.getStatus());
    }

    @Test
    void failed_toFailedPermanently_legal() {
        Job job = jobWithStatus(JobStatus.FAILED);
        stateMachine.transition(job, JobStatus.FAILED_PERMANENTLY, UUID.randomUUID());
        assertEquals(JobStatus.FAILED_PERMANENTLY, job.getStatus());
        assertNotNull(job.getCompletedAt());
    }

    @Test
    void retrying_toQueued_legal() {
        Job job = jobWithStatus(JobStatus.RETRYING);
        stateMachine.transition(job, JobStatus.QUEUED, UUID.randomUUID());
        assertEquals(JobStatus.QUEUED, job.getStatus());
    }

    @Test
    void cancelling_toCancelled_legal() {
        Job job = jobWithStatus(JobStatus.CANCELLING);
        stateMachine.transition(job, JobStatus.CANCELLED, UUID.randomUUID());
        assertEquals(JobStatus.CANCELLED, job.getStatus());
    }

    @Test
    void created_toRunning_illegal() {
        Job job = jobWithStatus(JobStatus.CREATED);
        assertThrows(InvalidJobTransitionException.class,
                () -> stateMachine.transition(job, JobStatus.RUNNING, UUID.randomUUID()));
    }

    @Test
    void created_toFailed_illegal() {
        Job job = jobWithStatus(JobStatus.CREATED);
        assertThrows(InvalidJobTransitionException.class,
                () -> stateMachine.transition(job, JobStatus.FAILED, UUID.randomUUID()));
    }

    @Test
    void queued_toCompleted_illegal() {
        Job job = jobWithStatus(JobStatus.QUEUED);
        assertThrows(InvalidJobTransitionException.class,
                () -> stateMachine.transition(job, JobStatus.COMPLETED, UUID.randomUUID()));
    }

    @Test
    void running_toQueued_illegal() {
        Job job = jobWithStatus(JobStatus.RUNNING);
        assertThrows(InvalidJobTransitionException.class,
                () -> stateMachine.transition(job, JobStatus.QUEUED, UUID.randomUUID()));
    }

    @Test
    void completed_toAnything_illegal() {
        Job job = jobWithStatus(JobStatus.COMPLETED);
        for (JobStatus s : JobStatus.values()) {
            if (s == JobStatus.COMPLETED) continue;
            assertThrows(InvalidJobTransitionException.class,
                    () -> stateMachine.transition(job, s, UUID.randomUUID()),
                    "COMPLETED -> " + s + " should be illegal");
        }
    }

    @Test
    void cancelled_toAnything_illegal() {
        Job job = jobWithStatus(JobStatus.CANCELLED);
        for (JobStatus s : JobStatus.values()) {
            if (s == JobStatus.CANCELLED) continue;
            assertThrows(InvalidJobTransitionException.class,
                    () -> stateMachine.transition(job, s, UUID.randomUUID()),
                    "CANCELLED -> " + s + " should be illegal");
        }
    }

    @Test
    void failedPermanently_toAnything_illegal() {
        Job job = jobWithStatus(JobStatus.FAILED_PERMANENTLY);
        for (JobStatus s : JobStatus.values()) {
            if (s == JobStatus.FAILED_PERMANENTLY) continue;
            assertThrows(InvalidJobTransitionException.class,
                    () -> stateMachine.transition(job, s, UUID.randomUUID()),
                    "FAILED_PERMANENTLY -> " + s + " should be illegal");
        }
    }

    @Test
    void canCancel_trueForCreatedQueuedRunning() {
        assertTrue(stateMachine.canCancel(JobStatus.CREATED));
        assertTrue(stateMachine.canCancel(JobStatus.QUEUED));
        assertTrue(stateMachine.canCancel(JobStatus.RUNNING));
    }

    @Test
    void canCancel_falseForOtherStates() {
        assertFalse(stateMachine.canCancel(JobStatus.COMPLETED));
        assertFalse(stateMachine.canCancel(JobStatus.CANCELLED));
        assertFalse(stateMachine.canCancel(JobStatus.FAILED));
        assertFalse(stateMachine.canCancel(JobStatus.RETRYING));
        assertFalse(stateMachine.canCancel(JobStatus.CANCELLING));
        assertFalse(stateMachine.canCancel(JobStatus.FAILED_PERMANENTLY));
    }
}
