package com.sunbreathingcode.PaginationDemo.controller;

import com.sunbreathingcode.PaginationDemo.dto.ItemResponse;
import com.sunbreathingcode.PaginationDemo.dto.PagedResponse;
import com.sunbreathingcode.PaginationDemo.service.ItemService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    // Passes the raw page/size/sort params straight to the service — the
    // whitelist check and Pageable construction are business rules, not
    // controller concerns.
    @GetMapping
    public ResponseEntity<PagedResponse<ItemResponse>> getItems(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sort) {
        return ResponseEntity.ok(itemService.getItems(page, size, sort));
    }
}
