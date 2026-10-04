# Decision: Implementing Keyset (Seek) Pagination

*Checkpoint 6 findings — the fix for the problem measured and documented in [[offset-pagination-at-scale]].*

---

## 1. The Core Idea

Offset pagination says: **"skip this many rows, then give me the next N."** Keyset pagination says something different: **"I last saw id 1,000,000 — give me the next N rows after that."**

The difference matters because of what each instruction lets the database do:

- `OFFSET` forces Postgres to count past every row before the one you want, every single time.
- `WHERE id > 1000000` lets Postgres use the index to jump straight to that position, the same way you'd flip a dictionary open roughly to the right letter instead of starting from "A" every time.

This is a "seek," not a "skip" — hence the name.

---

## 2. What We Built

A new endpoint, kept separate from the existing offset-based `GET /items`:

```
GET /items/seek?after=<lastSeenId>&size=20
```

**Request flow:**

```
ItemController.seekItems(@Valid @ModelAttribute SeekRequestDto)
        ▼
ItemService.seekItems(after, size)
        │  SELECT * FROM items WHERE id > :after ORDER BY id ASC LIMIT size+1
        ▼
ItemRepository.findByIdGreaterThanOrderByIdAsc(after, PageRequest.of(0, size+1))
        ▼
SeekResponse { content, size, hasNext, nextAfter }
```

**Key building blocks:**

- **`SeekRequestDto`** — `after` (defaults to `0`, meaning "start from the beginning," since real ids start at `1`) and `size`, validated with the same `@Min`/`@Max` pattern as offset pagination's `PageRequestDto`.
- **`SeekResponse<T>`** — intentionally has **no `totalElements` or `totalPages`**. Keyset genuinely cannot answer "how many pages are there" without a separate, expensive count — so the response doesn't pretend to.
- **The `size + 1` trick** — the query actually asks for one row *more* than requested. If that extra row comes back, we know there's a next page (`hasNext = true`) and trim it off. If not, we just reached the end. This avoids a second `COUNT(*)` query just to answer "is there more?"
- **`nextAfter`** — the id of the last row in the current page. The client passes this straight back as `after` on the next call — that's the whole "cursor."

---

## 3. Why It's Scoped to `id` Only (For Now)

The design doc's example sorts by `(createdAt, id)`, not just `id`. We deliberately built the simpler version first:

- **`id` is already unique and strictly increasing.** `WHERE id > :after` alone gives a stable, unambiguous position — no two rows can tie.
- **`createdAt` is *not* guaranteed unique.** Two items could have the exact same timestamp. If you sorted by `createdAt` alone, `WHERE createdAt > :lastSeen` could skip or repeat rows whenever timestamps collide — which is exactly the correctness bug keyset pagination is supposed to fix, not reintroduce. Fixing that requires a **composite cursor**: `WHERE (createdAt, id) < (:lastCreatedAt, :lastId)`, using `id` purely as a tiebreaker.

We chose to prove the core mechanism correctly on the simple, unique-key case first, rather than build the more complex composite-cursor version before confirming the basic idea works. Extending this to arbitrary sort fields (matching the whitelist from Checkpoint 3) is a real next step, not something this checkpoint claims to have solved.

---

## 4. Measured Results

Same comparison point as Checkpoint 5 — the last page of the 2-million-row table:

| Approach | Query | Execution time |
|---|---|---|
| Offset (Checkpoint 5) | `OFFSET 1999980 LIMIT 20` | 590 ms |
| Keyset (this checkpoint) | `WHERE id > 1999990 LIMIT 21` | **0.058 ms** |

`EXPLAIN ANALYZE` confirms why:

```
Index Scan using items_pkey on items (actual time=0.015..0.017 rows=10 loops=1)
  Index Cond: (id > 1999990)
```

No counting past 2 million rows — the index condition jumps straight to the right spot, regardless of how deep into the table that spot is. This is what "flat performance regardless of depth" (Section 5 of the design doc) looks like in practice, not just in theory.

Manually verified end-to-end against the running app too: paging forward with successive `nextAfter` values returns the correct next rows, invalid input (`after=-1`, `size=500`) returns the same `400` shape Checkpoint 4 established, and the last page correctly reports `hasNext: false`.

---

## 5. The Trade-off We Accepted

| | Offset (`GET /items`) | Keyset (`GET /items/seek`) |
|---|---|---|
| Jump to an arbitrary page number | Yes | No — only "next from here" |
| Performance at depth | Degrades linearly | Stays flat |
| Total count / total pages | Yes, cheaply | No — not exposed |
| Sortable by any whitelisted field | Yes | Not yet — `id` only |

Both endpoints exist side by side on purpose. This project isn't claiming keyset is strictly better — it's demonstrating that it's better for *one specific problem* (deep pages on a large, sequentially-accessed dataset) at the cost of giving up arbitrary page jumps and cheap totals. Which one a real API should offer depends on what the client actually needs: an admin UI with page-number buttons wants offset; an infinite-scroll feed wants keyset.

---

## 6. Summary

- Keyset pagination replaces "skip N rows" with "continue after the last row I saw," letting the database seek via the index instead of counting.
- Built as a separate endpoint (`GET /items/seek`) so both pagination styles stay directly comparable in this project.
- Scoped to sorting by `id` only for now, because `id` is unique — avoids the composite-cursor complexity needed for non-unique sort fields like `createdAt`, which remains a known, intentional gap.
- Measured against the same 2M-row dataset and the same deep-page scenario as Checkpoint 5: 590 ms (offset) vs. 0.058 ms (keyset) — confirming the fix actually solves the problem that was measured, not just a theoretical one.
