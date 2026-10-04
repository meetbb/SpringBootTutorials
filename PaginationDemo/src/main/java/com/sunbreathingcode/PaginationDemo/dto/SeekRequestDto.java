package com.sunbreathingcode.PaginationDemo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;

// Bound from GET /items/seek query params via @ModelAttribute. after=0
// (the default) means "start from the beginning" — valid because real item
// ids start at 1, so id > 0 matches every row.
@Getter
@Setter
public class SeekRequestDto {

    @Min(value = 0, message = "after must be >= 0")
    private long after = 0;

    @Min(value = 1, message = "size must be between 1 and 100")
    @Max(value = 100, message = "size must be between 1 and 100")
    private int size = 20;
}
