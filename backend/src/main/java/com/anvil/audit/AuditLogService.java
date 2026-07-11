package com.anvil.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);

    private final AuditLogEntryRepository repository;

    public AuditLogService(AuditLogEntryRepository repository) {
        this.repository = repository;
    }

    public void log(UUID actorUserId, String action, String targetType, UUID targetId, String metadata) {
        String jsonMetadata = metadata != null ? "{\"message\":\"" + escapeJson(metadata) + "\"}" : null;
        AuditLogEntry entry = new AuditLogEntry(actorUserId, action, targetType, targetId, jsonMetadata);
        repository.save(entry);
        log.info("Audit: actor={} action={} target={}/{}", actorUserId, action, targetType, targetId);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
