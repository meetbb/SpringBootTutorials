# System Design — Pagination & Sorting for a Large-Dataset API

*Standalone design doc — not yet tied to a project directory. Written the same way as `authservice/Documentation/SYSTEM_DESIGN.md`: a whiteboard-level plan, checkpointed, simple enough to start coding from.*

---

## 1. The Problem

`GET /items` that returns `findAll()` works fine at 500 rows. It breaks in three distinct ways once the table grows large:

- **Memory/bandwidth** — loading 2 million rows into a `List`, serializing them to JSON, and shipping that over the wire will OOM the server or the client, or just time out.
- **Client usefulness** — no UI renders 2 million rows anyway. The client almost always wants "the next 20," not "everything."
- **Database cost** — even if you only return 20, a naive database query still has to do real work to find *which* 20, and that cost shape changes completely depending on *how* you ask for them (this is the crux of the whole design, in Checkpoint 5).

So "pagination" isn't really a UI nicety — it's the API's way of saying "give me a bounded, predictable slice of a dataset whose full size I will never load at once." Sorting matters alongside it because "page 3" is meaningless unless the underlying order is stable — without a defined sort, the database is free to return rows in a different order on every call (no inherent order is guaranteed without `ORDER BY`), and pages would silently skip or repeat rows.

---

## 2. High-Level Picture

```
Client (page=2&size=20&sort=createdAt,desc)
        │
        ▼
Controller — parses + validates query params into a typed request
        │
        ▼
Service — applies business rules (max page size, default sort, whitelisted sort fields)
        │
        ▼
Repository (Spring Data JPA) — Pageable/Sort → real SQL with LIMIT/OFFSET or a keyset WHERE clause
        │
        ▼
Postgres — index-backed lookup, returns exactly one page's worth of rows
        │
        ▼
Response envelope — data + pagination metadata (not just a bare array)
```

**Key idea:** pagination is a *contract* between client and server about how much data crosses the wire per call — and the implementation of that contract (offset vs. keyset) is an internal detail that should be swappable without breaking the API shape, right up until the data volume forces a breaking change anyway (Checkpoint 6 explains why).

---

## 3. Core Components

| Layer | Responsibility |
|---|---|
| `PageRequestDto` | Validated input: `page`, `size`, `sort` — never trust raw query params directly. |
| `ItemController` | Exposes `GET /items`, parses query params, delegates to service. No business logic. |
| `ItemService` | Enforces max page size, default/allowed sort fields, builds the `Pageable`. |
| `ItemRepository` | Extends `PagingAndSortingRepository`/`JpaRepository` — Spring Data turns `Pageable` into `LIMIT`/`OFFSET`/`ORDER BY` automatically. |
| `PagedResponse<T>` | Response envelope: `content`, `page`, `size`, `totalElements`, `totalPages`, `hasNext`. |
| Postgres | Needs a **composite index** matching the sort + filter columns — without it, every page scan degrades as the table grows, regardless of pagination style. |

---

## 4. Implementation Checkpoints

### Checkpoint 1 — Project Setup + Seeded Large Dataset
Spring Web, Spring Data JPA, Postgres, Validation. Seed the table with a realistic large row count (e.g. 1–5 million rows via a batch insert script) — pagination problems that matter (Checkpoint 5) are invisible below ~100k rows, so testing against a small dataset would hide the exact thing this project exists to teach.
**Depends on:** nothing.

### Checkpoint 2 — Basic Offset Pagination
`GET /items?page=0&size=20`. Build a `Pageable` from the request and pass it straight to `itemRepository.findAll(pageable)`. Spring Data JPA returns a `Page<Item>`, which already carries `totalElements`/`totalPages` — wrap it in your own `PagedResponse<T>` DTO rather than returning Spring's `Page` type directly (don't leak a framework type into your public API contract).
**Depends on:** Checkpoint 1.

### Checkpoint 3 — Sorting, With a Whitelist
`GET /items?sort=createdAt,desc`. Parse the `sort` param into a `Sort` object. **Never pass the client's raw field name straight into `Sort.by(field)`** — validate it against a fixed allow-list of real, indexed entity fields first. An unvalidated sort field is a minor information-disclosure/DoS vector (a client could request `sort=someExpensiveUnindexedColumn` and force a full table sort) and, if ever built via string concatenation instead of Spring Data's `Sort` API, a SQL-injection vector too.
**Depends on:** Checkpoint 2.

### Checkpoint 4 — Response Envelope + Input Validation
Reject (`400`) a `size` above a hard cap (e.g. 100) — an unbounded `size` defeats the entire point of pagination (a client requesting `size=999999` is functionally back to `findAll()`). Default `page=0&size=20&sort=id,asc` when params are omitted, so "no sort specified" never means "undefined order" (ties back to Section 1 — an unordered page is not a stable page).
**Depends on:** Checkpoint 2, 3.

### Checkpoint 5 — Why Offset Pagination Breaks at Scale (the pivot point)
This is the checkpoint that actually justifies this project's existence. Explain and *measure* (via `EXPLAIN ANALYZE`) why `OFFSET 500000 LIMIT 20` is slow: Postgres can't jump straight to row 500,000 — it has to scan and discard the first 500,000 matching rows on *every single request* for a deep page, even with a perfect index on the sort column. Page 1 is fast; page 25,000 is not, and it gets linearly worse. This is the "so what" moment: the same shape as `authservice`'s pivot from "pure stateless JWT" to "needs a DB-backed refresh token" — the simple approach is correct and sufficient up to a real, demonstrable limit, then a known trade-off becomes unavoidable.
**Depends on:** Checkpoint 4 (need a real dataset size and a working baseline to measure against).

### Checkpoint 6 — Keyset (Cursor) Pagination
`GET /items?after=<lastSeenId>&size=20` (or an opaque base64 cursor encoding the last sort key's value(s)). Instead of `OFFSET`, the query becomes `WHERE (createdAt, id) < (:lastCreatedAt, :lastId) ORDER BY createdAt DESC, id DESC LIMIT 20` — a "seek," not a "skip." The database uses the index to jump directly to the right spot every time, so performance stays flat (`O(log n)`) no matter how deep into the dataset the client pages, instead of degrading linearly. The trade-off, which must be explained honestly: you lose random access to an arbitrary page number ("jump to page 500") and can't easily show total page counts without a separate count query — keyset only supports "next"/"previous" from where you are.
**Depends on:** Checkpoint 5 (must understand *why* this is needed before building it — don't jump straight here).

### Checkpoint 7 — Indexing Strategy
A composite B-tree index on exactly the columns used in both the `WHERE` filter and the `ORDER BY` clause, in the same order (e.g. `(created_at, id)` for Checkpoint 6's query) — a sort/seek that isn't backed by a matching index still falls back to a full scan regardless of which pagination style is used. Verify every allow-listed sortable field (Checkpoint 3) actually has backing index support; an allow-listed-but-unindexed sort field is a trap this checkpoint exists to close.
**Depends on:** Checkpoint 3, 6.

### Checkpoint 8 — Tests
Unit tests for the whitelist rejection and size-cap validation; an integration test proving keyset pagination returns the full dataset exactly once with no duplicates/gaps across pages, even while rows are concurrently inserted (`OFFSET` pagination can skip or repeat rows under concurrent writes — real keyset pagination is also what fixes that correctness bug, not just the performance one).
**Depends on:** whichever checkpoint the test targets, per project convention — added only on request.

---

## 5. Offset vs. Keyset — the trade-off, stated plainly

| | Offset (`page`/`size`) | Keyset (`after`/cursor) |
|---|---|---|
| Jump to arbitrary page number | Yes | No — sequential only |
| Performance on deep pages | Degrades linearly with offset | Stays flat |
| Correct under concurrent inserts/deletes | No — can skip/repeat rows | Yes |
| Shows total page count cheaply | Yes (one extra `COUNT`) | Only with a separate, possibly stale count |
| Implementation complexity | Lower — Spring Data's `Pageable` natively | Higher — hand-built cursor encoding/decoding |

**Default recommendation:** build offset pagination first (Checkpoints 2–4) because it's simpler and correct for small-to-medium result sets or admin UIs with page-number controls; graduate to keyset (Checkpoint 6) specifically for the large-dataset, infinite-scroll-style endpoint this project is meant to demonstrate. Don't build keyset everywhere by default — that's solving a problem most endpoints don't have.

---

## 6. Why This Evolves (the "so what")

- **The API contract can mostly survive the swap** — `page`/`size`/`sort` query params become an opaque `cursor` param, but the response envelope shape (`content`, `hasNext`) barely changes, so this is a good lesson in designing a response contract that doesn't lock you into one pagination strategy forever.
- **The real lesson isn't "keyset is better"** — it's that **the simple, obvious implementation (offset) is the right starting point**, and the decision to move to something more complex should be driven by a measured, demonstrated limit (Checkpoint 5's `EXPLAIN ANALYZE`), not adopted upfront on the assumption that you'll eventually need it. Same philosophy as `authservice`'s refresh-token design: start stateless/simple, add complexity only when a real requirement forces it.
