package com.sunbreathingcode.PaginationDemo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

// Bound from GET /items query params via @ModelAttribute. Mutable with a
// no-arg constructor (not a record) because Spring's @ModelAttribute binding
// sets fields through setters, not a constructor.
@Getter
@Setter
public class PageRequestDto {

    @Min(value = 0, message = "page must be >= 0")
    private int page = 0;

    @Min(value = 1, message = "size must be between 1 and 100")
    @Max(value = 100, message = "size must be between 1 and 100")
    private int size = 20;

    private String sort;
}
