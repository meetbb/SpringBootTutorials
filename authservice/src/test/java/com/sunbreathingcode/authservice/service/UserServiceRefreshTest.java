package com.sunbreathingcode.authservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.sunbreathingcode.authservice.dto.RefreshRequest;
import com.sunbreathingcode.authservice.dto.RefreshResponse;
import com.sunbreathingcode.authservice.entity.RefreshToken;
import com.sunbreathingcode.authservice.entity.User;
import com.sunbreathingcode.authservice.exception.InvalidRefreshTokenException;
import com.sunbreathingcode.authservice.repository.RefreshTokenRepository;
import com.sunbreathingcode.authservice.repository.UserRepository;
import com.sunbreathingcode.authservice.security.JwtService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class UserServiceRefreshTest {

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

    // With a refresh token that is signed, unexpired, tagged "refresh", and still
    // present in Postgres, refresh() should hand back a brand-new access token.
    @Test
    void refresh_returnsNewAccessToken_whenRefreshTokenIsValidAndKnown() {
        User user = new User();
        user.setId(1L);
        user.setEmail("meet@sunbreathingcode.com");

        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken(REFRESH_TOKEN);

        when(jwtService.isTokenValid(REFRESH_TOKEN)).thenReturn(true);
        when(jwtService.isRefreshToken(REFRESH_TOKEN)).thenReturn(true);
        when(refreshTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.of(new RefreshToken()));
        when(jwtService.extractEmail(REFRESH_TOKEN)).thenReturn("meet@sunbreathingcode.com");
        when(userRepository.findByEmail("meet@sunbreathingcode.com")).thenReturn(Optional.of(user));
        when(jwtService.generateAccessToken(user)).thenReturn("new-access-token");

        RefreshResponse response = userService.refresh(request);

        assertThat(response.getAccessToken()).isEqualTo("new-access-token");
    }

    // A tampered or expired refresh token fails JwtService's own signature/expiry
    // check first — refresh() must not even reach the DB lookup in that case.
    @Test
    void refresh_throwsInvalidRefreshToken_whenTokenIsNotSignedOrExpired() {
        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken(REFRESH_TOKEN);

        when(jwtService.isTokenValid(REFRESH_TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> userService.refresh(request))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    // A well-signed access token must not work here — only a token tagged
    // "type": "refresh" is accepted by this endpoint.
    @Test
    void refresh_throwsInvalidRefreshToken_whenTokenIsAnAccessToken() {
        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken(REFRESH_TOKEN);

        when(jwtService.isTokenValid(REFRESH_TOKEN)).thenReturn(true);
        when(jwtService.isRefreshToken(REFRESH_TOKEN)).thenReturn(false);

        assertThatThrownBy(() -> userService.refresh(request))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }

    // A refresh token that is cryptographically valid but whose hash was never
    // stored (or was deleted, e.g. after logout) must still be rejected — this is
    // the server-side revocation hook a pure JWT check alone can't provide.
    @Test
    void refresh_throwsInvalidRefreshToken_whenHashNotFoundInDatabase() {
        RefreshRequest request = new RefreshRequest();
        request.setRefreshToken(REFRESH_TOKEN);

        when(jwtService.isTokenValid(REFRESH_TOKEN)).thenReturn(true);
        when(jwtService.isRefreshToken(REFRESH_TOKEN)).thenReturn(true);
        when(refreshTokenRepository.findByTokenHash(anyString()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.refresh(request))
                .isInstanceOf(InvalidRefreshTokenException.class);
    }
}
