package com.anvil.auth;

import com.anvil.api.dto.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;
    private final LoginRateLimiter rateLimiter;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtProvider jwtProvider,
                       LoginRateLimiter rateLimiter) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtProvider = jwtProvider;
        this.rateLimiter = rateLimiter;
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException(request.email());
        }

        String hash = passwordEncoder.encode(request.password());
        User user = new User(request.email(), hash, Role.USER);
        user = userRepository.save(user);

        log.info("User registered: {}", user.getEmail());
        return toResponse(user);
    }

    public AuthResponse login(LoginRequest request, String clientIp) {
        rateLimiter.checkRateLimit(request.email(), clientIp);

        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new InvalidCredentialsException());

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            rateLimiter.recordFailure(request.email(), clientIp);
            throw new InvalidCredentialsException();
        }

        if (!user.isActive()) {
            throw new AccountDisabledException();
        }

        rateLimiter.reset(request.email(), clientIp);

        String accessToken = jwtProvider.generateAccessToken(user);
        String refreshToken = jwtProvider.generateRefreshToken();

        RefreshToken rt = new RefreshToken(refreshToken, user,
                Instant.now().plusMillis(jwtProvider.getRefreshTokenExpiryMs()));
        refreshTokenRepository.save(rt);

        log.info("User logged in: {}", user.getEmail());
        return new AuthResponse(accessToken, refreshToken, "Bearer",
                jwtProvider.getRefreshTokenExpiryMs() / 1000);
    }

    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(request.refreshToken())
                .orElseThrow(() -> new InvalidRefreshTokenException());

        if (!refreshToken.isValid()) {
            throw new InvalidRefreshTokenException();
        }

        User user = refreshToken.getUser();
        String newAccessToken = jwtProvider.generateAccessToken(user);
        String newRefreshToken = jwtProvider.generateRefreshToken();

        refreshToken.setRevoked(true);
        refreshTokenRepository.save(refreshToken);

        RefreshToken newRt = new RefreshToken(newRefreshToken, user,
                Instant.now().plusMillis(jwtProvider.getRefreshTokenExpiryMs()));
        refreshTokenRepository.save(newRt);

        return new AuthResponse(newAccessToken, newRefreshToken, "Bearer",
                jwtProvider.getRefreshTokenExpiryMs() / 1000);
    }

    @Transactional
    public void logout(RefreshTokenRequest request) {
        refreshTokenRepository.findByToken(request.refreshToken())
                .ifPresent(rt -> {
                    rt.setRevoked(true);
                    refreshTokenRepository.save(rt);
                });
    }

    private UserResponse toResponse(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getRole(),
                user.isActive(), user.getCreatedAt());
    }
}
