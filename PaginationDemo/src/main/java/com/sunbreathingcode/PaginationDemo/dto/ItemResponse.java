package com.sunbreathingcode.PaginationDemo.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ItemResponse {

    private Long id;
    private String name;
    private LocalDateTime createdAt;
}
