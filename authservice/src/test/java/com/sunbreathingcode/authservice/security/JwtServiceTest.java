package com.sunbreathingcode.authservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunbreathingcode.authservice.entity.User;
import java.util.Date;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-test-secret-key-32bytes!";
    private static final long ACCESS_TOKEN_EXPIRATION_MS = 900_000; // 15 min
    private static final long REFRESH_TOKEN_EXPIRATION_MS = 604_800_000; // 7 days

    private JwtService jwtService;
    private User user;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, ACCESS_TOKEN_EXPIRATION_MS, REFRESH_TOKEN_EXPIRATION_MS);

        user = new User();
        user.setId(1L);
        user.setEmail("meet@sunbreathingcode.com");
    }

    // A generated token must carry the same email we put in, since the filter (Checkpoint 6)
    // will rely on extractEmail() to know who is making the request.
    @Test
    void extractEmail_returnsEmailUsedToGenerateToken() {
        String token = jwtService.generateAccessToken(user);

        assertThat(jwtService.extractEmail(token)).isEqualTo(user.getEmail());
    }

    // A token generated with a valid signing key and a non-expired window should pass
    // validation — this is the "happy path" every login/filter call depends on.
    @Test
    void isTokenValid_returnsTrue_forFreshlyGeneratedToken() {
        String token = jwtService.generateAccessToken(user);

        assertThat(jwtService.isTokenValid(token)).isTrue();
    }

    // If a single character of the token is altered after signing, the signature check
    // must fail — this is what makes a JWT tamper-evident rather than just readable.
    @Test
    void isTokenValid_returnsFalse_forTamperedToken() {
        String token = jwtService.generateAccessToken(user);
        String tamperedToken = token.substring(0, token.length() - 1)
                + (token.charAt(token.length() - 1) == 'a' ? 'b' : 'a');

        assertThat(jwtService.isTokenValid(tamperedToken)).isFalse();
    }

    // A token whose expiration has already passed must be rejected, even though its
    // signature is perfectly valid — expiry and signature are two independent checks.
    @Test
    void isTokenValid_returnsFalse_forExpiredToken() throws InterruptedException {
        JwtService shortLivedJwtService = new JwtService(SECRET, -1, -1);
        String expiredToken = shortLivedJwtService.generateAccessToken(user);

        assertThat(jwtService.isTokenValid(expiredToken)).isFalse();
    }

    // Garbage input (not even a well-formed JWT) should be rejected safely instead of
    // throwing — isTokenValid() must be a safe yes/no check for any caller.
    @Test
    void isTokenValid_returnsFalse_forMalformedToken() {
        assertThat(jwtService.isTokenValid("not-a-valid-jwt")).isFalse();
    }

    // A refresh token must carry its own "type": "refresh" claim so it's
    // distinguishable from an access token by anyone inspecting it later.
    @Test
    void isRefreshToken_returnsTrue_forGeneratedRefreshToken() {
        String refreshToken = jwtService.generateRefreshToken(user);

        assertThat(jwtService.isRefreshToken(refreshToken)).isTrue();
    }

    // An access token must not be mistakable for a refresh token — this is what
    // lets the filter reject a refresh token presented as a Bearer access token.
    @Test
    void isRefreshToken_returnsFalse_forAccessToken() {
        String accessToken = jwtService.generateAccessToken(user);

        assertThat(jwtService.isRefreshToken(accessToken)).isFalse();
    }

    // extractExpiration() is what UserService uses to mirror a refresh token's
    // JWT expiry onto its Postgres row, instead of recomputing the TTL by hand.
    @Test
    void extractExpiration_returnsFutureDate_forFreshlyGeneratedRefreshToken() {
        String refreshToken = jwtService.generateRefreshToken(user);

        assertThat(jwtService.extractExpiration(refreshToken)).isAfter(new Date());
    }
}
