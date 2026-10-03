package com.sunbreathingcode.PaginationDemo.controller;

import com.sunbreathingcode.PaginationDemo.dto.ItemResponse;
import com.sunbreathingcode.PaginationDemo.dto.PagedResponse;
import com.sunbreathingcode.PaginationDemo.service.ItemService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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

    // Builds the Pageable explicitly from page/size rather than letting Spring
    // auto-bind a Pageable argument (which would also accept a raw, unvalidated
    // "sort" param) — sorting is deliberately not wired in until it has a
    // whitelist to validate against.
    @GetMapping
    public ResponseEntity<PagedResponse<ItemResponse>> getItems(
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Pageable pageable = PageRequest.of(page, size);
        return ResponseEntity.ok(itemService.getItems(pageable));
    }
}
