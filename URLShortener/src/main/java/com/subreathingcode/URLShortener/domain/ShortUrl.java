package com.subreathingcode.URLShortener.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Persistence entity for a shortened URL.
 * <p>
 * {@code id} is the auto-increment primary key. {@code shortCode} is derived
 * from {@code id} (Base62-encoded) *after* the first insert, since the ID
 * isn't known until the database assigns it — see
 * {@code ShortUrlService#createShortUrl}.
 */
@Entity
@Table(name = "short_urls")
@Getter
@Setter
@NoArgsConstructor
public class ShortUrl {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Nullable at the DB level for the brief moment between the first insert
    // and the follow-up update that sets it (see ShortUrlService).
    @Column(name = "short_code", unique = true, length = 10)
    private String shortCode;

    @Column(name = "original_url", nullable = false, length = 2048)
    private String originalUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
