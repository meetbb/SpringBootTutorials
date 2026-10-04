package com.sunbreathingcode.PaginationDemo.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

// Keyset's response envelope deliberately has no totalElements/totalPages —
// unlike offset pagination, getting a total count cheaply isn't possible
// here, so we only expose what keyset can answer: this page's content, and
// what cursor to pass next.
@Getter
@AllArgsConstructor
public class SeekResponse<T> {

    private List<T> content;
    private int size;
    private boolean hasNext;
    private Long nextAfter;
}
