package com.sunbreathingcode.PaginationDemo.controller;

import com.sunbreathingcode.PaginationDemo.dto.ItemResponse;
import com.sunbreathingcode.PaginationDemo.dto.PagedResponse;
import com.sunbreathingcode.PaginationDemo.dto.PageRequestDto;
import com.sunbreathingcode.PaginationDemo.service.ItemService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    // @Valid rejects an out-of-range page/size before this method body runs,
    // so an invalid request never reaches ItemService. The sort whitelist
    // check still happens in the service — it depends on a dynamic set of
    // allowed fields, not a fixed range Bean Validation can express.
    @GetMapping
    public ResponseEntity<PagedResponse<ItemResponse>> getItems(@Valid @ModelAttribute PageRequestDto request) {
        return ResponseEntity.ok(itemService.getItems(request.getPage(), request.getSize(), request.getSort()));
    }
}
