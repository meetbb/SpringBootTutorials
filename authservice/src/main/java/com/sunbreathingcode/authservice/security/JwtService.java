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

    private final SecretKey signingKey;
    private final long accessTokenExpirationMs;

    public JwtService(
            @Value("${jwt.secret}") String secret,
            @Value("${jwt.access-token-expiration-ms}") long accessTokenExpirationMs) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes());
        this.accessTokenExpirationMs = accessTokenExpirationMs;
    }

    // Signs a new access token for a user: subject = email, plus a userId claim,
    // issued-at, and an expiry computed from the configured TTL.
    public String generateAccessToken(User user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + accessTokenExpirationMs);

        return Jwts.builder()
                .subject(user.getEmail())
                .claim("userId", user.getId())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    // Pulls the subject (email) back out of a token's claims. Callers are expected
    // to have already confirmed the token is valid before trusting this value.
    public String extractEmail(String token) {
        return extractAllClaims(token).getSubject();
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
