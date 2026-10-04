package com.sunbreathingcode.PaginationDemo.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sunbreathingcode.PaginationDemo.entity.Item;
import com.sunbreathingcode.PaginationDemo.exception.InvalidSortFieldException;
import com.sunbreathingcode.PaginationDemo.repository.ItemRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

// Unit test only — ItemRepository is mocked, so this never touches the real
// database. We're checking one thing: does ItemService reject a sort field
// that isn't on the whitelist, before any query is built?
class ItemServiceSortTest {

    private final ItemRepository itemRepository = mock(ItemRepository.class);
    private final ItemService itemService = new ItemService(itemRepository);

    // Scenario: a client asks to sort by a column that isn't indexed or
    // doesn't exist (e.g. "sort=someExpensiveColumn"). This should be
    // rejected immediately with a clear error, and the repository should
    // never be called — the whole point of validating before querying.
    @Test
    void rejectsSortFieldNotOnWhitelist() {
        assertThatThrownBy(() -> itemService.getItems(0, 20, "someExpensiveColumn"))
                .isInstanceOf(InvalidSortFieldException.class)
                .hasMessageContaining("someExpensiveColumn");

        verifyNoInteractions(itemRepository);
    }

    // Scenario: a client asks to sort by a field that IS on the whitelist
    // ("name"). This should succeed and reach the repository — proving the
    // whitelist doesn't accidentally block legitimate, allowed fields.
    @Test
    void allowsSortFieldOnWhitelist() {
        Page<Item> emptyPage = new PageImpl<>(List.of());
        when(itemRepository.findAll(any(Pageable.class))).thenReturn(emptyPage);

        itemService.getItems(0, 20, "name,asc");

        verify(itemRepository).findAll(any(Pageable.class));
    }
}
