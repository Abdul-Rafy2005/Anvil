package com.anvil.api;

import com.anvil.admin.DeadLetterService;
import com.anvil.job.domain.DeadLetterEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/dlq")
public class DlqController {

    private final DeadLetterService deadLetterService;

    public DlqController(DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @GetMapping
    public ResponseEntity<?> list(
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) Boolean resolved,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<DeadLetterEntry> entries = deadLetterService.listEntries(
                jobType, resolved, PageRequest.of(page, size));
        return ResponseEntity.ok(Map.of(
                "content", entries.getContent(),
                "page", entries.getNumber(),
                "size", entries.getSize(),
                "totalElements", entries.getTotalElements(),
                "totalPages", entries.getTotalPages()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeadLetterEntry> get(@PathVariable UUID id) {
        return ResponseEntity.ok(deadLetterService.getEntry(id));
    }

    @PostMapping("/{id}/requeue")
    public ResponseEntity<DeadLetterEntry> requeue(@PathVariable UUID id,
                                                    Authentication authentication) {
        UUID adminUserId = UUID.fromString(authentication.getName());
        DeadLetterEntry entry = deadLetterService.requeue(id, adminUserId);
        return ResponseEntity.ok(entry);
    }

    @PostMapping("/{id}/discard")
    public ResponseEntity<DeadLetterEntry> discard(@PathVariable UUID id,
                                                    Authentication authentication) {
        UUID adminUserId = UUID.fromString(authentication.getName());
        DeadLetterEntry entry = deadLetterService.discard(id, adminUserId);
        return ResponseEntity.ok(entry);
    }
}
