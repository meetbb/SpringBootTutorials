package com.sunbreathingcode.PaginationDemo.service;

import com.sunbreathingcode.PaginationDemo.dto.ItemResponse;
import com.sunbreathingcode.PaginationDemo.dto.PagedResponse;
import com.sunbreathingcode.PaginationDemo.entity.Item;
import com.sunbreathingcode.PaginationDemo.repository.ItemRepository;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

@Service
public class ItemService {

    private final ItemRepository itemRepository;

    public ItemService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    // Fetches one page of items straight from the repository and maps it into
    // our own response envelope, rather than returning Spring Data's Page<T>
    // directly — the API contract shouldn't leak a framework type.
    public PagedResponse<ItemResponse> getItems(Pageable pageable) {
        Page<Item> result = itemRepository.findAll(pageable);

        List<ItemResponse> content = result.getContent().stream()
                .map(item -> new ItemResponse(item.getId(), item.getName(), item.getCreatedAt()))
                .toList();

        return new PagedResponse<>(
                content,
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages(),
                result.hasNext());
    }
}
