# Decision: Closing the Allow-listed-but-Unindexed Gap

*Checkpoint 7 findings — depends on the sort whitelist from Checkpoint 3 and the keyset work in [[keyset-pagination]].*

---

## 1. The Gap We Found

Since Checkpoint 3, `ItemService` has whitelisted three sortable fields:

```java
private static final Set<String> ALLOWED_SORT_FIELDS = Set.of("id", "name", "createdAt");
```

Whitelisting a field only protects against sorting by something *arbitrary*. It says nothing about whether that field is actually *fast* to sort by. We checked, and only `id` had real index support — it's covered automatically by the primary key. `name` and `created_at` had no index at all.

That's a trap precisely because it's invisible from the API: `GET /items?sort=name,asc` looks exactly as valid and well-formed as `GET /items?sort=id,asc`. The whitelist says "yes, this is allowed." It doesn't say "yes, this is fast."

---

## 2. What We Measured (Before Any Fix)

```sql
EXPLAIN ANALYZE SELECT * FROM items ORDER BY name ASC LIMIT 20;
EXPLAIN ANALYZE SELECT * FROM items ORDER BY created_at DESC LIMIT 20;
```

| Sort field | Plan | Execution time |
|---|---|---|
| `name` | `Parallel Seq Scan` + in-memory sort | 412 ms |
| `created_at` | `Parallel Seq Scan` + in-memory sort | 164 ms |

The important detail: this is **page 0** — `LIMIT 20`, no `OFFSET` at all. Checkpoint 5's slowdown only showed up on *deep* pages. This problem shows up on the very first request. Without an index, Postgres has no choice but to read every single row in the table, sort all of them, and only then hand back the first 20 — there's no way to "sort smarter" without something that already tells it the order in advance.

---

## 3. The Fix

Added an index for each whitelisted field that didn't already have one, directly on the `Item` entity:

```java
@Table(
        name = "items",
        indexes = {
            @Index(name = "idx_items_name", columnList = "name"),
            @Index(name = "idx_items_created_at", columnList = "created_at")
        })
public class Item { ... }
```

`id` needed nothing new — the primary key already provides a B-tree index on it. With `spring.jpa.hibernate.ddl-auto=update`, Hibernate created both indexes against the live 2-million-row table on the next app startup — no manual SQL needed, consistent with how this project manages schema so far.

---

## 4. What We Measured (After the Fix)

Same two queries, same table, nothing else changed:

| Sort field | Plan | Execution time |
|---|---|---|
| `name` | `Index Scan using idx_items_name` | **0.164 ms** |
| `created_at` | `Index Scan Backward using idx_items_created_at` | **0.263 ms** |

`name` went from 412 ms to 0.164 ms — roughly **2,500x faster** — just by giving Postgres an index it could use instead of reading and sorting the whole table. Verified through the actual API too (`GET /items?sort=name,asc`, `GET /items?sort=createdAt,desc`) — correct ordering, same response shape as before.

---

## 5. Why Single-Column Indexes Were Enough Here

The design doc's general rule is: build a **composite** index matching *both* the `WHERE` filter and the `ORDER BY` columns, in that order — because an index only helps a query that actually uses its leading columns the way it was built.

We didn't need a composite index for `name` or `created_at`, because nothing in the current code filters on one column while sorting by another — each query here is "sort by this one column," full stop. A single-column B-tree index is exactly what that needs. Adding a composite index now, for a query shape that doesn't exist yet, would be solving a problem we don't have.

**Where a composite index would matter:** if [[keyset-pagination]] were extended to seek on `createdAt` (not just `id`), the query would become `WHERE (created_at, id) < (:lastCreatedAt, :lastId) ORDER BY created_at DESC, id DESC` — and *that* query would need a composite `(created_at, id)` index to stay fast, because it filters and sorts on both columns together. We intentionally haven't built that extension yet (noted as a known gap in [[keyset-pagination]]), so that composite index doesn't exist yet either — there would be no query to back.

---

## 6. Summary

- A whitelist (Checkpoint 3) controls *which* fields a client can sort by. It does not guarantee any of them are indexed — that's a separate, silent gap.
- `name` and `created_at` were allow-listed but unindexed; sorting by either forced a full-table scan-and-sort, even on page 0.
- Added one single-column B-tree index per field via `@Table(indexes = ...)` on the `Item` entity; Hibernate created them automatically against the live table.
- Measured improvement: `name` sort went from 412 ms to 0.164 ms; `created_at` sort went from 164 ms to 0.263 ms.
- Composite indexes weren't needed yet because no current query filters and sorts across columns together — that need only arises if keyset pagination is later extended beyond `id`.
