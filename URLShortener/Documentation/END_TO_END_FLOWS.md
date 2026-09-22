# End-to-End Flows — URLShortener

## Write flow — creating a short URL

### 1. Entry point
`ShortUrlController.createShortUrl()`, bound to:
```
POST /api/urls
```

### 2. Example input
```
POST /api/urls
Content-Type: application/json

{
  "url": "https://www.amazon.com/products/electronics/laptop?ref=homepage&session=xyz123"
}
```
Maps into a `CreateShortUrlRequest` DTO with a single `url` field.

### 3. How the input is processed

**Step A — Validation (automatic, before controller logic runs)**
`@Valid` triggers bean validation on the DTO:
- `@NotBlank` — `url` isn't empty
- `@URL` — `url` is shaped like a valid URL

If either fails, the request never reaches business logic — it's routed to the global exception handler, which returns `400 Bad Request` with a message like `{"error": "url must be a valid URL"}`.

**Step B — Controller delegates to the service**
The controller has no business logic — it calls `shortUrlService.createShortUrl(request.getUrl())` and nothing else.

**Step C — Service does the work (`ShortUrlService.createShortUrl`)**
Runs inside a database transaction (`@Transactional`):
1. Creates a new entity with only `originalUrl` set (no short code yet) and saves it. The database assigns an auto-increment ID (e.g., `125`).
2. Base62-encodes that ID (e.g., `125` → `"cb"`) using the character set `0-9A-Za-z`.
3. Sets the resulting code on the same entity and saves it again.

Both saves are in the same transaction, so no other part of the system ever observes a row with a missing short code.

### 4. Output
The service returns the fully-populated entity: `id`, `shortCode`, `originalUrl`, `createdAt`. The controller builds a response DTO exposing only what the client needs:
```json
{
  "shortCode": "cb",
  "originalUrl": "https://www.amazon.com/products/electronics/laptop?ref=homepage&session=xyz123"
}
```
(`id` and `createdAt` are intentionally not exposed — the DTO avoids leaking internal database details.)

### 5. How it's returned
```
HTTP/1.1 201 Created
Content-Type: application/json

{
  "shortCode": "cb",
  "originalUrl": "https://www.amazon.com/products/electronics/laptop?ref=homepage&session=xyz123"
}
```
`201 Created` is the correct status for "a new resource was created," as opposed to the more generic `200 OK`.

---

## Read/redirect flow — using a short URL

### 1. Entry point
`ShortUrlController.redirect()`, bound to:
```
GET /{shortCode}
```
A wildcard path — any single path segment routes here via the `{shortCode}` path variable.

### 2. Example input
```
GET /cb
```
No request body. The entire input is the path variable `shortCode = "cb"`. There's no shape validation here (unlike the write flow) — any string is accepted as a candidate; correctness is determined by whether a match exists, not by the string's format.

### 3. How the input is processed

**Step A — Controller delegates immediately**
`shortUrlService.getByShortCode("cb")`.

**Step B — Service does the lookup (`ShortUrlService.getByShortCode`)**
Runs inside a read-only transaction (`@Transactional(readOnly = true)`). Calls the repository's derived query method `findByShortCode("cb")`, which Spring Data JPA auto-generates from the method name (`SELECT * FROM short_urls WHERE short_code = ?`) — no manual SQL written.

**Step C — Branch on the result**
- **Found:** the entity is returned up to the controller.
- **Not found:** `.orElseThrow(...)` throws `ShortUrlNotFoundException("cb")`, which propagates past the controller to the global exception handler.

### 4. Output

**Happy path:** the entity comes back to the controller, which uses only `originalUrl` to build the redirect target.

**Not-found path:** no entity — just the exception, converted by the global handler into:
```json
{"error": "No URL found for short code: cb"}
```

### 5. How it's returned

**Happy path — a redirect, not JSON:**
```
HTTP/1.1 302 Found
Location: https://www.amazon.com/products/electronics/laptop?ref=homepage&session=xyz123
```
No response body (`ResponseEntity<Void>`). The browser automatically follows the `Location` header — the user never sees an intermediate page.

`302 Found` (temporary redirect) is used instead of `301 Moved Permanently` deliberately: a `301` tells browsers/CDNs to cache the redirect indefinitely, which would be wrong if a short code's destination could ever change, and would also undermine future click-tracking since repeat visits might never reach the server again.

**Not-found path — plain JSON error:**
```
HTTP/1.1 404 Not Found
Content-Type: application/json

{"error": "No URL found for short code: cb"}
```

---

## Both flows together

```
POST /api/urls  →  201 + {shortCode, originalUrl}   (create the mapping)
GET  /{code}    →  302 + Location header             (use the mapping, success)
                →  404 + JSON error                   (mapping doesn't exist)
```

One flow writes the lookup table; the other reads it. In a real deployment, reads (redirects) will vastly outnumber writes (creates) — a detail that motivates optimizations like caching the read path (see `INTERVIEW_QA.md`, section 4).
