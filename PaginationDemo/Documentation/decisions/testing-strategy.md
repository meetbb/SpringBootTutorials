# Decision: Testing Strategy for Pagination, Validation, and Keyset Correctness

*Checkpoint 8 findings — the final checkpoint, closing the loop on the sort whitelist ([[input-validation-placement]]) and keyset pagination ([[keyset-pagination]]).*

---

## 1. What We Set Out to Test

The design doc named two things for this checkpoint:

1. Unit tests for the sort whitelist rejection and the size-cap validation.
2. An integration test proving keyset pagination returns a full dataset exactly once, with no duplicates or gaps, even while rows are being concurrently inserted.

The first thing worth noting: by the time we got here, validation didn't live where it originally did. Size-cap validation moved from a hand-written `if` in `ItemService` into Bean Validation annotations on `PageRequestDto` back in [[input-validation-placement]]. So "test the size-cap validation" now correctly means testing the DTO, not the service — the tests target where the logic actually lives today, not where the design doc's first draft assumed it would be.

---

## 2. Unit Tests — Sort Whitelist (`ItemServiceSortTest`)

These use a **mocked** `ItemRepository` (Mockito) — no Spring context, no real database, fast and isolated.

- **Rejects an unknown sort field** — and, just as important, asserts the repository is *never called* (`verifyNoInteractions`). This proves validation happens before any query is built, not as an afterthought once something's already gone wrong.
- **Accepts a whitelisted field** (`name`) — a whitelist test is incomplete if it only proves rejection; it also has to prove legitimate values aren't accidentally caught by the same net.

---

## 3. Unit Tests — Size-Cap Validation (`PageRequestDtoValidationTest`)

These call `jakarta.validation.Validator` directly against `PageRequestDto` — no Spring, no HTTP layer, just the validation rule itself.

- Defaults (`page=0`, `size=20`) produce no violations.
- `page=-1` → exactly one violation, message `"page must be >= 0"`.
- `size=999999` → exactly one violation, message `"size must be between 1 and 100"`.
- `size=0` → same violation — the cap also has a floor; a page of zero rows is just as meaningless as a page of a million.

Testing at this level means the test would catch a broken rule even if someone later swapped out *how* Spring wires the DTO into the controller — the rule's correctness and the framework's plumbing are two different things, tested separately.

---

## 4. Integration Test — Keyset Correctness (`KeysetPaginationIntegrationTest`)

This one needs a real database — the whole point is proving the actual SQL keyset pagination generates is correct, not just the Java around it. Like the existing `PaginationDemoApplicationTests`, it connects to the same local Postgres container the app itself uses (no Testcontainers/H2 in this project).

**Keeping it safe to run against real data:** the table already holds 2 million real seeded rows. Inserting test data directly into it and forgetting to clean up would quietly corrupt the dataset every future checkpoint measures against. So every test:
- Inserts a small, clearly-named batch of rows (`"KeysetTest-0"`, `"KeysetTest-1"`, ...).
- Starts paging from the **current max id**, not from the beginning — otherwise every test run would first page through all 2 million existing rows just to reach the handful we actually care about.
- Deletes exactly the rows it inserted in `@AfterEach`, by id. Verified manually after the full suite ran: `SELECT count(*) FROM items` still reports exactly 2,000,000, and zero rows matching the test naming pattern are left behind.

**Test 1 — full traversal, no duplicates or gaps.** Inserts 25 rows, pages through all of them 7 at a time (deliberately not a divisor of 25, so the last page is partial — the "clean division" case would hide an off-by-one bug). Collects every id seen across every page into a list, then asserts: the list is exactly the size of what was inserted (catches duplicates — a duplicate would make the list longer than the inserted set), and it matches the inserted ids exactly in any order (catches gaps — a missing id would fail this check).

**Test 2 — a concurrent insert doesn't corrupt an in-progress pagination.** This is the specific scenario the design doc calls out as **offset pagination's actual bug, not just its slowness**: if a new row is inserted between a reader's two page fetches, `OFFSET`-based paging can shift every row after the insertion point — causing the next page to skip a row or repeat one, because "page 2" is defined by position, and position just changed.

The test: fetch page 1 (10 rows), then simulate another writer inserting a new row, then fetch page 2 (10 rows). Keyset pagination defines page 2 as "whatever comes after the last row I saw" — a value, not a position — so a new row elsewhere can't change what that means. The test asserts page 2 is exactly the 10 rows it should be, and that page 1 and page 2 share no rows.

**Why this isn't a literal multi-threaded race:** a real concurrent writer running on a separate thread while the test pages would prove the same point, but introduces timing-dependent flakiness without adding anything the sequential version doesn't already demonstrate — the property being tested ("a row inserted after I started paging doesn't break my next fetch") only depends on *when* the insert happens relative to the two reads, not on literal thread interleaving. The sequential version is the simplest test that actually exercises that condition.

---

## 5. Result

9 tests total, all passing:

| Test class | Type | What it protects |
|---|---|---|
| `PaginationDemoApplicationTests` | Smoke test | App context starts |
| `ItemServiceSortTest` | Unit (mocked) | Sort whitelist rejects/accepts correctly, before querying |
| `PageRequestDtoValidationTest` | Unit (no Spring) | page/size validation rules, independent of the framework wiring |
| `KeysetPaginationIntegrationTest` | Integration (real Postgres) | Keyset pagination is complete, duplicate-free, and resilient to concurrent inserts |

---

## 6. Summary

- Tests target where logic actually lives today (the DTO for size-cap validation), not where the original design sketch placed it — a reminder that tests should follow the code, not a stale diagram.
- Unit tests for the whitelist prove both directions: bad fields are rejected before querying, good fields aren't accidentally blocked.
- The keyset integration test proves the specific correctness property that matters — full, duplicate-free, gap-free traversal — and specifically exercises the one failure mode offset pagination is structurally prone to (a page shifting because of a concurrent insert).
- Test data is inserted and torn down by id against the real 2M-row dataset, confirmed by a manual row-count check after the full suite ran — the dataset every earlier checkpoint's measurements depend on stays untouched.
