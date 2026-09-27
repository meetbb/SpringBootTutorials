package com.sunbreathingcode.authservice.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sunbreathingcode.authservice.dto.RefreshRequest;
import com.sunbreathingcode.authservice.exception.InvalidRefreshTokenException;
import com.sunbreathingcode.authservice.repository.RefreshTokenRepository;
import com.sunbreathingcode.authservice.repository.UserRepository;
import com.sunbreathingcode.authservice.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceLogoutTest {

    private static final String REFRESH_TOKEN = "signed-refresh-token";

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, refreshTokenRepository, passwordEncoder, jwtService);
    }

    // A signed, unexpired, correctly-typed refresh token should have its row
    // deleted — that's what makes it unusable at /auth/refresh afterwards.
    @Test
    void logout_deletesTokenByHash_whenRefreshTokenIsValid() {
        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken(REFRESH_TOKEN);

        when(jwtService.isTokenValid(REFRESH_TOKEN)).thenReturn(true);
        when(jwtService.isRefreshToken(REFRESH_TOKEN)).thenReturn(true);

        userService.logout(request);

        verify(refreshTokenRepository).deleteByTokenHash(anyString());
    }

    // A tampered or expired token fails JwtService's own check first — logout()
    // must not attempt a DB delete in that case.
    @Test
    void logout_throwsInvalidRefreshToken_whenTokenIsNotSignedOrExpired() {
        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken(REFRESH_TOKEN);

        when(jwtService.isTokenValid(REFRESH_TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> userService.logout(request))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).deleteByTokenHash(anyString());
    }

    // An access token must not be usable to log out via this endpoint — only a
    // token tagged "type": "refresh" is accepted.
    @Test
    void logout_throwsInvalidRefreshToken_whenTokenIsAnAccessToken() {
        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken(REFRESH_TOKEN);

        when(jwtService.isTokenValid(REFRESH_TOKEN)).thenReturn(true);
        when(jwtService.isRefreshToken(REFRESH_TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> userService.logout(request))
                .isInstanceOf(InvalidRefreshTokenException.class);

        verify(refreshTokenRepository, never()).deleteByTokenHash(anyString());
    }
}
