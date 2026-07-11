package com.anvil.api;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    @GetMapping("/ping")
    public ResponseEntity<Map<String, String>> ping(Authentication authentication) {
        return ResponseEntity.ok(Map.of(
                "message", "Admin access confirmed",
                "userId", authentication.getName()));
    }
}
