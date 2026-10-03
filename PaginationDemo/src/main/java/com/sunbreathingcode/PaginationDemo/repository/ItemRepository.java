package com.sunbreathingcode.PaginationDemo.repository;

import com.sunbreathingcode.PaginationDemo.entity.Item;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItemRepository extends JpaRepository<Item, Long> {
}
