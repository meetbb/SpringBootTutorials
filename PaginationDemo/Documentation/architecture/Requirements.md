# Requirements & API Design — Q&A

### 1. What API endpoints should this project expose?

Just one, for the Checkpoint 2–4 scope:

```
GET /items
```

This project exists to teach pagination/sorting, not full CRUD, so no `POST`, `GET /items/{id}`, etc. for now. If keyset pagination is added later (Checkpoint 6), it reuses this same endpoint with a different query-param shape rather than a second endpoint — the pagination strategy is an implementation detail, not a different resource.

### 2. What should the request and response of each endpoint look like?

**Request** — query parameters, no body (it's a `GET`):

```
GET /items?page=0&size=20&sort=createdAt,desc
```

Parsed in the controller into a validated DTO (`PageRequestDto`) rather than passing loose `@RequestParam`s around.

**Response** — an envelope, not a bare array:

```json
{
  "content": [ { "id": 101, "name": "...", "createdAt": "..." } ],
  "page": 0,
  "size": 20,
  "totalElements": 2000000,
  "totalPages": 100000,
  "hasNext": true
}
```

A bare array gives the client no way to know if there's a next page, the total count, or what page it's on. The envelope costs one extra JSON level and gives the client everything needed to build pagination UI.

### 3. How should pagination parameters be represented in the API?

Query parameters (`?page=0&size=20`), not path segments, not a request body. `GET` conventionally carries no body, pagination is view/filter metadata (not a resource identifier, so it doesn't belong in the path), and query params are naturally optional with defaults.

### 4. Page-number based or offset based, and why?

Page-number based (`page`, `size`) for this stage — Spring Data JPA's native `Pageable` model, and what Checkpoints 2–4 build around.

Note: "page-number" and "offset" aren't competing options — page-number is a friendlier way of expressing an offset (`page=2&size=20` → `OFFSET 40 LIMIT 20` internally). The real fork is offset/page-based vs. keyset/cursor-based (see design doc Section 5):

| | Offset (`page`/`size`) | Keyset (`after`/cursor) |
|---|---|---|
| Jump to arbitrary page | Yes | No — sequential only |
| Performance on deep pages | Degrades linearly | Stays flat |
| Correct under concurrent writes | No | Yes |
| Cheap total page count | Yes | No |
| Implementation complexity | Lower | Higher |

Start with page-based because it's simpler and what Spring Data gives for free, and the project's point is to *feel* why it breaks (Checkpoint 5) before reaching for the fix (Checkpoint 6) — not to skip straight to keyset.

### 5. No pagination parameters provided — what happens?

Apply defaults, don't error: `page=0&size=20&sort=id,asc` (Checkpoint 4). A bare `GET /items` is a completely normal first request. `sort` specifically should never default to "no sort" — without `ORDER BY`, row order isn't guaranteed and pages could skip or repeat rows (design doc Section 1).

### 6. Invalid pagination parameters — what happens?

Reject with `400 Bad Request` and a clear message. Don't silently clamp or guess:

- `size` above the hard cap (e.g. >100) → `400`, not silently clamped to 100 (clamping hides the problem and masks bugs).
- `page` negative → `400`.
- `sort` field not on the whitelist → `400`, never silently ignored or passed straight into `Sort.by(field)` (Checkpoint 3).

Handled centrally via the global exception handler, returning a consistent error shape rather than a stack trace.

### 7. Requested page does not exist — what happens?

`200 OK` with an empty `content` array, not `404`. `/items` exists — the client asked for a page past the end of a valid collection, which is a normal, successful empty result, not a missing resource. Spring Data's `Page<T>` already behaves this way for free (`findAll(pageable)` past the end returns an empty `content` list).

### 8. What information should the API return along with the actual data?

The `PagedResponse<T>` envelope fields, each answering one concrete client question:

- `content` — the data.
- `page`, `size` — what was asked for (echoed back).
- `totalElements`, `totalPages` — how big the whole collection is (needed for page-number UI).
- `hasNext` — can the client ask for more (needed for a "Next" button without manual math).

Don't add more fields (`hasPrevious`, `isFirst`, `isLast`, etc.) just because `Page` happens to expose them — add only on concrete need.

### 9. How should sorting be requested by the client?

Query param, same request as pagination: `?sort=createdAt,desc`. This is the format Spring Data's `Sort` parses natively via `Pageable` binding, and it generalizes to multiple sort keys by repeating the param (`?sort=createdAt,desc&sort=id,asc`).

### 10. Single field or multiple fields?

Start with single-field sorting — multi-field isn't load-bearing for the offset-vs-keyset lesson (Checkpoints 5/6), which only needs one stable, indexed sort column.

Caveat: a single sort column isn't stable if it has duplicate values (ties can flip order between pages). The fix used in Checkpoint 6's query (`ORDER BY createdAt DESC, id DESC`) is for the service layer to always silently append `id` as a secondary tiebreaker — not to expose multi-field sort as a client-facing feature.

### 11. Which fields should be allowed for sorting?

Only fields that are both indexed (Checkpoint 7 — an unindexed sort forces a full table scan regardless of pagination style) and meaningful to sort by. For `Item`, realistically `id` and `createdAt` (maybe `name`). Not every entity column by default.

### 12. Unsupported sort field requested — what happens?

`400 Bad Request` with a clear message listing the allowed fields. Don't silently fall back to the default sort and don't silently drop the field — both hide the client's mistake, and an overly permissive whitelist could leak internal schema/column names.

### 13. Should the API allow ascending and descending sorting?

Yes — `asc`/`desc` as the second part of the `sort` param, defaulting to `asc` if omitted. Nearly free via `Sort.Direction`, and both directions are equally normal requests (newest-first vs. oldest-first).

### 14. Should filtering be included in this project?

No — out of scope for Checkpoints 1–8. The design doc is scoped tightly to pagination/sorting at scale; filtering would add new query params and validation, and would change the indexing story (Checkpoint 7's composite index would need to match filter + sort columns together), diluting the specific lesson (offset's scan cost vs. keyset's seek). If wanted later, treat it as a separate follow-on project.

### 15. If filtering is included, how should it interact with pagination and sorting?

Conceptually: filter → sort the filtered set → paginate the sorted set. Not three independent operations — pagination and sorting only make sense relative to whichever result set the filter already narrowed down to.

### 16. Should the API support combining filtering, sorting, and pagination in a single request?

Yes, in principle — `?status=ACTIVE&sort=createdAt,desc&page=1&size=20` as one request, each a separate query param with no change to the envelope shape. The real cost is the composite index needing to cover filter column(s) *and* sort column(s) together (e.g. `(status, created_at, id)`), which is the main reason filtering is deferred (see Q14).
