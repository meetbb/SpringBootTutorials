package com.sunbreathingcode.PaginationDemo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// name and createdAt are both allow-listed sort fields (ItemService's
// ALLOWED_SORT_FIELDS), so each needs its own B-tree index — without one,
// sorting by it forces a full table scan regardless of how deep the page
// is, even on page 0. id doesn't need one here: it's already indexed by
// its primary key.
@Entity
@Table(
        name = "items",
        indexes = {
            @Index(name = "idx_items_name", columnList = "name"),
            @Index(name = "idx_items_created_at", columnList = "created_at")
        })
@Getter
@Setter
@NoArgsConstructor
public class Item {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        this.createdAt = LocalDateTime.now();
    }
}
