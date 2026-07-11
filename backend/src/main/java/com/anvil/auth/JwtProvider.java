package com.anvil.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtProvider {

    private final SecretKey key;
    private final long access_token_expiry_ms;
    private final long refresh_token_expiry_ms;

    public JwtProvider(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access_token_expiry_ms:900000}") long access_token_expiry_ms,
            @Value("${jwt.refresh_token_expiry_ms:604800000}") long refresh_token_expiry_ms) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.access_token_expiry_ms = access_token_expiry_ms;
        this.refresh_token_expiry_ms = refresh_token_expiry_ms;
    }

    public String generateAccessToken(User user) {
        Date now = new Date();
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("email", user.getEmail())
                .claim("role", user.getRole().name())
                .issuedAt(now)
                .expiration(new Date(now.getTime() + access_token_expiry_ms))
                .signWith(key)
                .compact();
    }

    public String generateRefreshToken() {
        return Jwts.builder()
                .subject(java.util.UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + refresh_token_expiry_ms))
                .signWith(key)
                .compact();
    }

    public long getRefreshTokenExpiryMs() {
        return refresh_token_expiry_ms;
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean validateToken(String token) {
        try {
            parseToken(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    public String getUserIdFromToken(String token) {
        return parseToken(token).getSubject();
    }

    public String getRoleFromToken(String token) {
        return parseToken(token).get("role", String.class);
    }
}
