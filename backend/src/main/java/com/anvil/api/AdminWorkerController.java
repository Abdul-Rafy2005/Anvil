package com.anvil.api;

import com.anvil.admin.AdminWorkerService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/workers")
public class AdminWorkerController {

    private final AdminWorkerService adminWorkerService;

    public AdminWorkerController(AdminWorkerService adminWorkerService) {
        this.adminWorkerService = adminWorkerService;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list() {
        return ResponseEntity.ok(adminWorkerService.listWorkers());
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Map<String, Object>> pause(@PathVariable UUID id, Authentication authentication) {
        UUID adminUserId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(adminWorkerService.pauseWorker(id, adminUserId));
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Map<String, Object>> resume(@PathVariable UUID id, Authentication authentication) {
        UUID adminUserId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(adminWorkerService.resumeWorker(id, adminUserId));
    }

    @PostMapping("/{id}/restart")
    public ResponseEntity<Map<String, Object>> restart(@PathVariable UUID id, Authentication authentication) {
        UUID adminUserId = UUID.fromString(authentication.getName());
        return ResponseEntity.ok(adminWorkerService.restartWorker(id, adminUserId));
    }
}
