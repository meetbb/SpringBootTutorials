package com.sunbreathingcode.PaginationDemo.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sunbreathingcode.PaginationDemo.dto.ItemResponse;
import com.sunbreathingcode.PaginationDemo.dto.SeekResponse;
import com.sunbreathingcode.PaginationDemo.entity.Item;
import com.sunbreathingcode.PaginationDemo.repository.ItemRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

// Integration test — runs against the real local Postgres (same database the
// app itself uses, no Testcontainers/H2), proving the actual SQL keyset
// pagination generates is correct, not just the Java around it. We insert a
// small, uniquely-named batch of test rows rather than touching the existing
// 2M-row dataset, and always clean them up afterward.
@SpringBootTest
class KeysetPaginationIntegrationTest {

    @Autowired
    private ItemRepository itemRepository;

    @Autowired
    private ItemService itemService;

    private final List<Long> insertedIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        itemRepository.deleteAllById(insertedIds);
        insertedIds.clear();
    }

    // Scenario: a client pages all the way through a known set of rows,
    // 7 at a time (deliberately not a divisor of 25, so the last page is
    // partial). Every row they inserted should come back exactly once —
    // no row skipped, none repeated — which is the core correctness
    // guarantee keyset pagination is supposed to provide.
    @Test
    void returnsEveryInsertedRowExactlyOnceWithNoDuplicatesOrGaps() {
        long startAfter = lastExistingId();
        List<Item> inserted = insertTestItems(25);
        insertedIds.addAll(inserted.stream().map(Item::getId).toList());

        List<Long> seenIds = new ArrayList<>();
        long cursor = startAfter;
        boolean hasNext = true;

        while (hasNext) {
            SeekResponse<ItemResponse> page = itemService.seekItems(cursor, 7);
            page.getContent().forEach(item -> seenIds.add(item.getId()));
            cursor = page.getNextAfter();
            hasNext = page.isHasNext();
        }

        assertThat(seenIds).hasSize(insertedIds.size());
        assertThat(seenIds).containsExactlyInAnyOrderElementsOf(insertedIds);
    }

    // Scenario: this is the specific case offset pagination gets wrong.
    // A reader fetches page 1, then — before fetching page 2 — a new row
    // is inserted by someone else. With OFFSET, that insert can shift every
    // row after it, causing the reader's next page to skip or repeat a row.
    // With keyset, page 2 is defined as "after the last row I saw," which a
    // new insert elsewhere can't change.
    @Test
    void newInsertBetweenPageFetchesDoesNotCausePageOverlap() {
        long startAfter = lastExistingId();
        List<Item> inserted = insertTestItems(20);
        insertedIds.addAll(inserted.stream().map(Item::getId).toList());

        SeekResponse<ItemResponse> page1 = itemService.seekItems(startAfter, 10);
        assertThat(page1.getContent()).hasSize(10);

        Item concurrentlyInserted = itemRepository.save(newTestItem("ConcurrentInsert"));
        insertedIds.add(concurrentlyInserted.getId());

        SeekResponse<ItemResponse> page2 = itemService.seekItems(page1.getNextAfter(), 10);

        List<Long> page1Ids = page1.getContent().stream().map(ItemResponse::getId).toList();
        List<Long> page2Ids = page2.getContent().stream().map(ItemResponse::getId).toList();
        List<Long> expectedPage2Ids =
                inserted.subList(10, 20).stream().map(Item::getId).toList();

        assertThat(page2Ids).containsExactlyElementsOf(expectedPage2Ids);
        assertThat(page1Ids).doesNotContainAnyElementsOf(page2Ids);
    }

    private long lastExistingId() {
        return itemRepository
                .findAll(PageRequest.of(0, 1, Sort.by(Sort.Direction.DESC, "id")))
                .getContent()
                .stream()
                .findFirst()
                .map(Item::getId)
                .orElse(0L);
    }

    private List<Item> insertTestItems(int count) {
        List<Item> items = IntStream.range(0, count)
                .mapToObj(i -> newTestItem("KeysetTest-" + i))
                .toList();
        return itemRepository.saveAll(items);
    }

    private Item newTestItem(String name) {
        Item item = new Item();
        item.setName(name);
        return item;
    }
}
