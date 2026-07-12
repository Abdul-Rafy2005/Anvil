package com.anvil.audit;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class AuditLogQueryService {

    private final AuditLogEntryRepository repository;

    public AuditLogQueryService(AuditLogEntryRepository repository) {
        this.repository = repository;
    }

    public Map<String, Object> query(UUID actorUserId, String action, UUID targetId,
                                     Instant since, Instant until, int page, int size) {
        Page<AuditLogEntry> entries = repository.findAllFiltered(
                actorUserId, action, targetId, since, until, PageRequest.of(page, size));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("content", entries.getContent().stream().map(e -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", e.getId().toString());
            item.put("actorUserId", e.getActorUserId() != null ? e.getActorUserId().toString() : null);
            item.put("action", e.getAction());
            item.put("targetType", e.getTargetType());
            item.put("targetId", e.getTargetId() != null ? e.getTargetId().toString() : null);
            item.put("metadata", e.getMetadata());
            item.put("createdAt", e.getCreatedAt().toString());
            return item;
        }).toList());
        response.put("page", entries.getNumber());
        response.put("size", entries.getSize());
        response.put("totalElements", entries.getTotalElements());
        response.put("totalPages", entries.getTotalPages());
        return response;
    }
}
