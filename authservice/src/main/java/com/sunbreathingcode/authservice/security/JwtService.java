package com.sunbreathingcode.authservice.security;

import com.sunbreathingcode.authservice.entity.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class JwtService {

    private static final String TOKEN_TYPE_CLAIM = "type";
    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String REFRESH_TOKEN_TYPE = "refresh";

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;
    private final long refreshTokenExpirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration-ms}") long accessTokenExpirationMs,
            @Value("${jwt.refresh-token-expiration-ms}") long refreshTokenExpirationMs) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessTokenExpirationMs = accessTokenExpirationMs;
        this.refreshTokenExpirationMs = refreshTokenExpirationMs;
    }

    // Signs a new access token for a user: subject = email, plus a userId claim,
    // issued-at, and an expiry computed from the configured TTL.
    public String generateAccessToken(User user) {
        return buildToken(user, ACCESS_TOKEN_TYPE, accessTokenExpirationMs);
    }

    // Same shape as an access token but longer-lived and tagged "type": "refresh",
    // so it can't be mistaken for (or accepted as) an access token by the filter.
    public String generateRefreshToken(User user) {
        return buildToken(user, REFRESH_TOKEN_TYPE, refreshTokenExpirationMs);
    }

    private String buildToken(User user, String tokenType, long expirationMs) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
                .subject(user.getEmail())
                .claim("userId", user.getId())
                .claim(TOKEN_TYPE_CLAIM, tokenType)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    // True only for a token whose "type" claim is "refresh" — lets callers reject
    // an access token presented at the /refresh endpoint, and vice versa.
    public boolean isRefreshToken(String token) {
        return REFRESH_TOKEN_TYPE.equals(extractAllClaims(token).get(TOKEN_TYPE_CLAIM, String.class));
    }

    // Pulls the subject (email) back out of a token's claims. Callers are expected
    // to have already confirmed the token is valid before trusting this value.
    public String extractEmail(String token) {
        return extractAllClaims(token).getSubject();
    }

    // Pulls the expiry out of a token's claims, so callers can mirror it (e.g. the
    // refresh token's DB row expiry) without hardcoding the TTL a second time.
    public Date extractExpiration(String token) {
        return extractAllClaims(token).getExpiration();
    }

    // Safe yes/no check for whether a token's signature and expiry are both good.
    // Never throws — any parsing failure is treated as "not valid".
    public boolean isTokenValid(String token) {
        try {
            extractAllClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    // Shared parsing step: verifies the signature against our secret key and
    // decodes the payload. Throws JwtException on any tampering or expiry issue.
    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
