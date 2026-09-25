package com.sunbreathingcode.authservice.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunbreathingcode.authservice.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "test-secret-key-test-secret-key-32bytes!";
    private static final long ACCESS_TOKEN_EXPIRATION_MS = 900_000; // 15 min

    private JwtService jwtService;
    private User user;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, ACCESS_TOKEN_EXPIRATION_MS);

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
        JwtService shortLivedJwtService = new JwtService(SECRET, -1);
        String expiredToken = shortLivedJwtService.generateAccessToken(user);

        assertThat(jwtService.isTokenValid(expiredToken)).isFalse();
    }

    // Garbage input (not even a well-formed JWT) should be rejected safely instead of
    // throwing — isTokenValid() must be a safe yes/no check for any caller.
    @Test
    void isTokenValid_returnsFalse_forMalformedToken() {
        assertThat(jwtService.isTokenValid("not-a-valid-jwt")).isFalse();
    }
}
