package com.sunbreathingcode.authservice.service;

import com.sunbreathingcode.authservice.dto.LoginRequest;
import com.sunbreathingcode.authservice.dto.LoginResponse;
import com.sunbreathingcode.authservice.dto.MeResponse;
import com.sunbreathingcode.authservice.dto.RefreshRequest;
import com.sunbreathingcode.authservice.dto.RefreshResponse;
import com.sunbreathingcode.authservice.dto.RegisterRequest;
import com.sunbreathingcode.authservice.dto.RegisterResponse;
import com.sunbreathingcode.authservice.entity.RefreshToken;
import com.sunbreathingcode.authservice.entity.User;
import com.sunbreathingcode.authservice.exception.EmailAlreadyExistsException;
import com.sunbreathingcode.authservice.exception.InvalidCredentialsException;
import com.sunbreathingcode.authservice.exception.InvalidRefreshTokenException;
import com.sunbreathingcode.authservice.exception.UserNotFoundException;
import com.sunbreathingcode.authservice.repository.RefreshTokenRepository;
import com.sunbreathingcode.authservice.repository.UserRepository;
import com.sunbreathingcode.authservice.security.JwtService;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public UserService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtService jwtService) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    // Creates a new user account. Rejects duplicate emails up front, then stores
    // only the bcrypt hash of the password — plaintext is never persisted.
    public RegisterResponse register(RegisterRequest request) {
        if (userRepository.findByEmail(request.getEmail()).isPresent()) {
            throw new EmailAlreadyExistsException(request.getEmail());
        }

        User user = new User();
        user.setEmail(request.getEmail());
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));

        User saved = userRepository.save(user);
        return new RegisterResponse(saved.getId(), saved.getEmail());
    }

    // Verifies email + password against stored records and, on success, issues a
    // signed access token plus a signed refresh token. Both failure paths throw
    // the same exception on purpose.
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(InvalidCredentialsException::new);

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }

        String accessToken = jwtService.generateAccessToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);
        saveRefreshToken(user, refreshToken);

        return new LoginResponse(accessToken, refreshToken);
    }

    // Exchanges a still-valid refresh token for a new access token. The JWT's own
    // signature/expiry is checked first, then its hash must still exist in
    // Postgres — that DB lookup is what will let Checkpoint 9 revoke a refresh
    // token early, since deleting the row invalidates it even though the JWT
    // itself would otherwise still parse as valid.
    public RefreshResponse refresh(RefreshRequest request) {
        String refreshToken = request.getRefreshToken();

        if (!jwtService.isTokenValid(refreshToken) || !jwtService.isRefreshToken(refreshToken)) {
            throw new InvalidRefreshTokenException();
        }

        refreshTokenRepository.findByTokenHash(hashToken(refreshToken))
                .orElseThrow(InvalidRefreshTokenException::new);

        String email = jwtService.extractEmail(refreshToken);
        User user = userRepository.findByEmail(email)
                .orElseThrow(InvalidRefreshTokenException::new);

        String accessToken = jwtService.generateAccessToken(user);
        return new RefreshResponse(accessToken);
    }

    private void saveRefreshToken(User user, String refreshToken) {
        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                jwtService.extractExpiration(refreshToken).toInstant(), ZoneId.systemDefault());

        RefreshToken tokenRecord = new RefreshToken();
        tokenRecord.setUserId(user.getId());
        tokenRecord.setTokenHash(hashToken(refreshToken));
        tokenRecord.setExpiresAt(expiresAt);
        refreshTokenRepository.save(tokenRecord);
    }

    // Deterministic SHA-256 hash used to look up a refresh token by value without
    // ever storing the raw token — unlike BCrypt, it produces the same output for
    // the same input, which is required for a WHERE tokenHash = ? lookup.
    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // Looks up the user behind an already-authenticated request (email comes from
    // the validated JWT, not client input) and maps it to a safe response DTO.
    public MeResponse getCurrentUser(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException(email));

        return new MeResponse(user.getId(), user.getEmail(), user.getRole(), user.getCreatedAt());
    }
}
