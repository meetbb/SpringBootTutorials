package com.subreathingcode.URLShortener.controller;

import com.subreathingcode.URLShortener.domain.ShortUrl;
import com.subreathingcode.URLShortener.dto.CreateShortUrlRequest;
import com.subreathingcode.URLShortener.dto.CreateShortUrlResponse;
import com.subreathingcode.URLShortener.service.ShortUrlService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequiredArgsConstructor
public class ShortUrlController {

    private final ShortUrlService shortUrlService;

    @PostMapping("/api/urls")
    public ResponseEntity<CreateShortUrlResponse> createShortUrl(@Valid @RequestBody CreateShortUrlRequest request) {
        ShortUrl shortUrl = shortUrlService.createShortUrl(request.getUrl());
        CreateShortUrlResponse response = new CreateShortUrlResponse(
                shortUrl.getShortCode(), shortUrl.getOriginalUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        ShortUrl shortUrl = shortUrlService.getByShortCode(shortCode);
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(shortUrl.getOriginalUrl()))
                .build();
    }
}
