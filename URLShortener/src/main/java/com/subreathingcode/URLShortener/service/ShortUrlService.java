package com.subreathingcode.URLShortener.service;

import com.subreathingcode.URLShortener.domain.ShortUrl;
import com.subreathingcode.URLShortener.exception.ShortUrlNotFoundException;
import com.subreathingcode.URLShortener.repository.ShortUrlRepository;
import com.subreathingcode.URLShortener.util.Base62Encoder;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ShortUrlService {

    private final ShortUrlRepository shortUrlRepository;

    /**
     * Creates a shortened URL.
     * <p>
     * This is a two-step write: we save once to let the DB assign the
     * auto-increment ID, then Base62-encode that ID into the short code and
     * save again. We can't compute the code before the first insert because
     * counter-based encoding only works once we know the actual ID.
     * Both writes happen in the same transaction, so a caller never observes
     * a row with a null short code.
     */
    @Transactional
    public ShortUrl createShortUrl(String originalUrl) {
        ShortUrl shortUrl = new ShortUrl();
        shortUrl.setOriginalUrl(originalUrl);

        ShortUrl saved = shortUrlRepository.save(shortUrl);
        saved.setShortCode(Base62Encoder.encode(saved.getId()));

        return shortUrlRepository.save(saved);
    }

    @Cacheable(value = "shortUrls", key = "#shortCode")
    @Transactional(readOnly = true)
    public ShortUrl getByShortCode(String shortCode) {
        return shortUrlRepository.findByShortCode(shortCode)
                .orElseThrow(() -> new ShortUrlNotFoundException(shortCode));
    }
}
