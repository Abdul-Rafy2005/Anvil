package com.anvil.api.dto;

import com.anvil.auth.Role;
import java.time.Instant;
import java.util.UUID;

public record UserResponse(
    UUID id,
    String email,
    Role role,
    boolean isActive,
    Instant createdAt
) {}
