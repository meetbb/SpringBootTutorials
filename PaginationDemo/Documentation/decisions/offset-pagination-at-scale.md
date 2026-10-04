# Decision: Why Offset Pagination Breaks Down at Scale

*Checkpoint 5 findings — the pivot point of this project. This is what actually justifies moving to keyset pagination (Checkpoint 6) instead of just assuming it upfront.*

---

## 1. The Analogy

Picture a printed phone book — thousands of names, sorted alphabetically, one per line. Someone asks: **"Give me names 1,000,020 through 1,000,040."**

There's no way to teleport to that spot. You have to open to page 1 and flip forward, counting names one by one, until you've passed 1,000,020 of them — only then do you start actually reading the 20 you wanted.

That's `OFFSET`: "flip past this many rows, *then* start reading." `LIMIT` is "stop after you've read this many." The flipping — not the reading — is the expensive part, and the further in you ask to go, the more flipping it takes.

A sorted phone book (like an indexed column) tells you the *order* things are in. It does not tell you *where* row 1,000,020 physically is. You still have to count your way there.

---

## 2. What We Measured

Same query shape, run against our real 2-million-row `items` table, varying only the offset:

```sql
EXPLAIN ANALYZE SELECT * FROM items ORDER BY id ASC LIMIT 20 OFFSET <N>;
```

| Offset | Rows the index scan had to walk past | Execution time |
|---|---|---|
| 0 | 20 | 2.4 ms |
| 500,000 | 500,020 | 86.7 ms |
| 1,000,000 | 1,000,020 | 183 ms |
| 1,500,000 | 1,500,020 | 445 ms |
| 1,999,980 (last page) | 2,000,000 | 590 ms |

Two things stand out:

- **"Rows scanned" grows in exact lockstep with the offset.** It's always `offset + 20`. This is the real cost — not an estimate.
- **Execution time tracks it, but isn't perfectly smooth**, because Postgres caches recently-touched pages in memory. A repeated query at the same offset gets faster the second time. That's not something to rely on in a real app — a cache that happens to be warm for one specific offset is luck, not a guarantee.

**The takeaway: the deeper the page, the more work every single request does — even though it only ever returns 20 rows.**

---

## 3. Why Having an Index Doesn't Save Us Here

This is the part worth being explicit about, because it's counter-intuitive: `items_pkey` is a *perfect* index for this query — a B-tree on exactly the column we sort by (`id`). And the query still gets slower as the offset grows.

That's because an index solves a different problem than the one `OFFSET` has. An index tells the database *what order the rows are in*, so it doesn't need to sort them from scratch. It does not tell the database *where offset 1,000,000 begins* — that still has to be counted, one row at a time, same as flipping pages in a sorted phone book. Indexing the right columns (a later checkpoint) is necessary for performance, but it does not fix this specific problem on its own.

---

## 4. The Trade-off, Plainly

| | Offset (`page`/`size` — what we have today) | Keyset (`cursor`/`after` — next checkpoint) |
|---|---|---|
| Jump to an arbitrary page number | Yes | No — only "next from here" |
| Performance on deep pages | Gets slower the deeper you go | Stays roughly the same no matter how deep |
| Correct if rows are being inserted while paging | No — can skip or repeat rows | Yes |
| Simplicity to build | Lower — Spring Data gives it to us for free | Higher — built by hand |

Offset pagination isn't "wrong" — it's the right default, and it's what we correctly built first (Checkpoints 2–4). This checkpoint's point is narrower: we now have *measured proof* of exactly where it stops being good enough, instead of guessing.

---

## 5. Why We're Changing Course Now, and Not Earlier

The whole point of doing this measurement before writing any new pagination code is: **don't build the more complex solution until you can point at a real, demonstrated limit of the simple one.** We just did that — the numbers above are that limit, demonstrated on our own data, not a rule we took on faith.

This is also why indexing strategy (covered later) comes *after* keyset pagination in this project's order, not before: a good index on the sort column was already in place here (`id` is the primary key), and it didn't prevent the slowdown. Indexing fixes a different problem; it's not a substitute for choosing the right pagination strategy for deep pages.

---

## 6. Summary

- `OFFSET N` means "count past N matching rows, then return the next page" — the database has no way to jump straight to a position without counting.
- We measured this directly: scan cost (and roughly, execution time) grows linearly with the offset, confirmed from page 1 up to the very last page of our 2M-row table.
- A good index on the sort column does not fix this — it solves a different problem (avoiding a full re-sort), not "finding a position without counting."
- This measured limit, not a general rule of thumb, is what justifies building keyset pagination next (Checkpoint 6), which replaces "skip N rows" with "continue after the last row I saw" — letting the database jump straight there instead of counting.
