package com.sunbreathingcode.PaginationDemo.service;

import com.sunbreathingcode.PaginationDemo.dto.ItemResponse;
import com.sunbreathingcode.PaginationDemo.dto.PagedResponse;
import com.sunbreathingcode.PaginationDemo.dto.SeekResponse;
import com.sunbreathingcode.PaginationDemo.entity.Item;
import com.sunbreathingcode.PaginationDemo.exception.InvalidSortFieldException;
import com.sunbreathingcode.PaginationDemo.repository.ItemRepository;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class ItemService {

    // Only fields that are both indexed and meaningful to sort by are exposed to
    // clients — never pass a client-supplied field straight into Sort.by(field),
    // since an unindexed field would force a full table sort on every request.
    private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "name", "createdAt");

    private final ItemRepository itemRepository;

    public ItemService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    // page/size are already validated by PageRequestDto's @Min/@Max before
    // this method is ever called — the controller rejects an invalid request
    // on its own, so there's nothing to re-check here.
    public PagedResponse<ItemResponse> getItems(int page, int size, String sort) {
        Pageable pageable = PageRequest.of(page, size, parseSort(sort));
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

    // Fetches size+1 rows so we can tell whether there's a next page without
    // a separate COUNT query — if we got one extra row, trim it and report
    // hasNext=true. "after=0" naturally means "from the beginning" since
    // real item ids start at 1.
    public SeekResponse<ItemResponse> seekItems(long after, int size) {
        List<Item> rows = itemRepository.findByIdGreaterThanOrderByIdAsc(after, PageRequest.of(0, size + 1));

        boolean hasNext = rows.size() > size;
        List<Item> page = hasNext ? rows.subList(0, size) : rows;

        List<ItemResponse> content = page.stream()
                .map(item -> new ItemResponse(item.getId(), item.getName(), item.getCreatedAt()))
                .toList();

        Long nextAfter = page.isEmpty() ? after : page.get(page.size() - 1).getId();

        return new SeekResponse<>(content, size, hasNext, nextAfter);
    }

    // Expects "field,direction" (e.g. "createdAt,desc"); direction defaults to
    // ascending if omitted. No sort param at all defaults to id,asc, so "no
    // sort specified" never means "undefined order" (an unordered page isn't
    // a stable page).
    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by(Sort.Direction.ASC, "id");
        }

        String[] parts = sort.split(",", 2);
        String field = parts[0].trim();

        if (!ALLOWED_SORT_FIELDS.contains(field)) {
            throw new InvalidSortFieldException(field, ALLOWED_SORT_FIELDS);
        }

        Sort.Direction direction = parts.length > 1 && "desc".equalsIgnoreCase(parts[1].trim())
                ? Sort.Direction.DESC
                : Sort.Direction.ASC;

        return Sort.by(direction, field);
    }
}
