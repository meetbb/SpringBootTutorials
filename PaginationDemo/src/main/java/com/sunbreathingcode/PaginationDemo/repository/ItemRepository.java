package com.sunbreathingcode.PaginationDemo.repository;

import com.sunbreathingcode.PaginationDemo.entity.Item;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {

    // The "seek" at the heart of keyset pagination: WHERE id > :after,
    // ORDER BY id ASC. The index on id lets Postgres jump straight to this
    // position instead of counting past every row before it.
    List<Item> findByIdGreaterThanOrderByIdAsc(Long after, Pageable pageable);
}
