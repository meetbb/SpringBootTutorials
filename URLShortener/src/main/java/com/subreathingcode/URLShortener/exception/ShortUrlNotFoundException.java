package com.subreathingcode.URLShortener.exception;

public class ShortUrlNotFoundException extends RuntimeException {

    public ShortUrlNotFoundException(String shortCode) {
        super("No URL found for short code: " + shortCode);
    }
}
