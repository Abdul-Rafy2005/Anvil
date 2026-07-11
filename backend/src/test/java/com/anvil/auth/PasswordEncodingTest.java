package com.anvil.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class PasswordEncodingTest {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    @Test
    void encode_producesHash() {
        String hash = encoder.encode("password123");
        assertNotNull(hash);
        assertNotEquals("password123", hash);
        assertTrue(hash.startsWith("$2a$"));
    }

    @Test
    void matches_correctPassword_returnsTrue() {
        String hash = encoder.encode("password123");
        assertTrue(encoder.matches("password123", hash));
    }

    @Test
    void matches_wrongPassword_returnsFalse() {
        String hash = encoder.encode("password123");
        assertFalse(encoder.matches("wrongpassword", hash));
    }

    @Test
    void encode_differentHashesForSamePassword() {
        String hash1 = encoder.encode("password123");
        String hash2 = encoder.encode("password123");
        assertNotEquals(hash1, hash2);
        assertTrue(encoder.matches("password123", hash1));
        assertTrue(encoder.matches("password123", hash2));
    }
}
