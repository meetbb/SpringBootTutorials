package com.sunbreathingcode.PaginationDemo.dto;

import java.time.Instant;
import lombok.Getter;

@Getter
public class ErrorResponse {

    private final Instant timestamp;
    private final int status;
    private final String message;

    public ErrorResponse(int status, String message) {
        this.timestamp = Instant.now();
        this.status = status;
        this.message = message;
    }
}
