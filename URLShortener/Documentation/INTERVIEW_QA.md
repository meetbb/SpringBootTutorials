# Interview Q&A — URLShortener

Common interview questions about this project, grouped by theme, with answers grounded in the actual implementation.

## 1. Project overview

**Q: What problem does this solve, and why does it matter?**
A: Long URLs are hard to share, type, remember, or fit in constrained spaces (SMS, printed material, verbal sharing). The service maps a long URL to a short, unique code and remembers that mapping so visitors to the short code get redirected to the original URL. It's fundamentally a lookup table with a clean API in front of it — the value is doing that reliably and fast, not algorithmic cleverness.

**Q: Walk me through what happens when I POST a URL.**
A: See `END_TO_END_FLOWS.md` — write flow section.

**Q: Walk me through what happens when someone visits a short link.**
A: See `END_TO_END_FLOWS.md` — read/redirect flow section.

## 2. Core design decision — short code generation

**Q: How do you generate the short code?**
A: The database auto-increment ID of the row is Base62-encoded (`0-9A-Za-z`, 62 characters) into a compact, URL-safe string.

**Q: Why not just hash the URL (MD5/SHA) and truncate it?**
A: Hashing can collide, and truncating a hash to keep it short increases collision risk further. You'd still need a uniqueness check and retry loop. Encoding a value that's already guaranteed unique (the DB's own primary key) sidesteps collisions entirely.

**Q: Why can't you compute the short code before inserting the row?**
A: The short code is derived from the row's ID, and the ID doesn't exist until the database assigns it on insert.

**Q: Walk me through why you need two writes instead of one.**
A: Insert the row (no code yet) to get the DB-assigned ID → Base62-encode that ID → update the same row with the resulting code. Both writes happen in one transaction, so no caller ever observes a half-written row with a missing code.

**Q: If you needed to avoid two writes for performance, what are the alternatives?**
A: Pre-allocate ID blocks (a sequence/counter service), use a Snowflake-style ID generator that doesn't depend on the DB round-trip, or generate a random code up front and check uniqueness before insert (with retry on collision).

## 3. Collisions, uniqueness, guessability

**Q: Can two requests ever get the same short code?**
A: No — codes are derived from an auto-increment primary key, which the database guarantees is unique.

**Q: Is there a security concern with how codes are generated?**
A: Yes — codes are sequential and guessable (`1→a`, `2→b`, ...), so someone could enumerate short URLs by incrementing the code. Fix: shuffle the ID's bits before encoding, or generate random codes with a uniqueness check.

**Q: What's the maximum number of unique codes with Base62 and length N?**
A: 62^N. For example, 62^6 ≈ 56 billion — why most real-world shorteners use 6-8 character codes.

## 4. Scaling / system design

**Q: This system is read-heavy — reads (redirects) vastly outnumber writes (creates). How would you optimize for that?**
A: This is implemented — `ShortUrlService.getByShortCode` is annotated `@Cacheable`, backed by Redis. A read checks Redis first; only on a miss does it fall through to Postgres, and the result is cached for subsequent reads. Writes are unaffected — they still go straight to the database, since creates are comparatively rare and the cache is only populated lazily by the first read of a given code (not written proactively on create).

**Q: How would you scale this to millions of requests per day?**
A: The Redis layer already absorbs most read traffic. Beyond that: add a Postgres read replica, consider a CDN/edge redirect layer, and horizontally scale app instances behind a load balancer.

**Q: If you have multiple app servers, does the current ID generation strategy still work?**
A: No — a single auto-increment column in one database becomes a contention point/bottleneck across instances. Would need a distributed ID generator (e.g., Snowflake IDs) or partitioned ID ranges per instance.

**Q: How would you add a custom alias feature (user picks their own short code)?**
A: A separate code path that explicitly checks uniqueness of the requested alias instead of deriving the code from an ID, rejecting if already taken.

**Q: How would you add expiring links?**
A: Add an `expiresAt` column, check it in the read path before redirecting, and optionally a scheduled cleanup job.

**Q: How would you add click analytics?**
A: Log each redirect, ideally asynchronously (e.g., to a separate table or event stream) so it doesn't add latency to the redirect response.

## 5. Database / persistence

**Q: What database does this use, and why?**
A: PostgreSQL, run locally via Docker Compose. It replaced an earlier in-memory H2 setup that lost all data on every restart — H2 was fine for the first milestone but not viable once the project needed to actually persist data. Tests still run against H2 in-memory, since they don't need durability and shouldn't require Docker to be running.

**Q: What index would you add, and why?**
A: A unique index on `short_code`, since every redirect performs a lookup on it. Without it, reads degrade to full table scans as the table grows.

**Q: Why is `shortCode` nullable in the schema if every finished row has one?**
A: Because of the two-step write — for the brief window between the first and second save, the column is null at the database level. That transient state is invisible to callers because both writes are inside the same transaction.

## 6. Redis caching

**Q: Where exactly does caching sit in the architecture?**
A: In the service layer only — `ShortUrlService.getByShortCode` is annotated `@Cacheable`. The controller and repository are unaware caching exists at all, which is a direct payoff of the layered architecture: the change was confined to one method.

**Q: Why is the cache only on the read path, not the write path?**
A: Creates are rare compared to redirects, so there's no performance need to cache them. The cache is populated lazily — the first read of a given short code is a cache miss that falls through to Postgres and populates Redis for next time.

**Q: How do you know the cache is actually being used, not just configured?**
A: Verified directly: created a URL, read it once (confirmed a `shortUrls::<id>` key appeared in Redis), deleted the row straight from Postgres bypassing the app, then read the same short code again — it still returned the correct `302` redirect, which could only have come from Redis since the row no longer existed in the database.

**Q: What happens to a "not found" lookup — does it get cached?**
A: No. `@Cacheable` only caches a method's return value, and a not-found lookup throws an exception instead of returning one, so 404s never pollute the cache. This was also verified directly (checked Redis keys before/after a 404 request — no new key appeared).

**Q: What serialization format do cached values use, and why not the default?**
A: JSON, via a custom `RedisCacheConfig`. Spring's default Redis cache value serializer is JDK serialization, which requires the cached class to implement `Serializable` — `ShortUrl` doesn't, and adding that just to satisfy a cache implementation detail would be an awkward coupling. JSON avoids it and keeps cached values human-readable in Redis for debugging.

**Q: What's a limitation of the current caching approach?**
A: No invalidation strategy. It's safe today only because short URLs are immutable once created — if a future feature allowed editing or deleting a short URL's destination, the create/update/delete paths would need to evict or refresh the corresponding cache entry, or reads could serve stale data indefinitely (within the TTL).

**Q: What if Redis goes down in production?**
A: Open question, not yet handled — current behavior on a Redis outage hasn't been tested. The reasonable design intent would be graceful degradation (fall through to direct DB reads rather than failing the request), but that isn't implemented.

## 7. Transactions / correctness

**Q: Why is the create operation wrapped in a transaction? What breaks without it?**
A: Without it, if the second save (setting the short code) failed, you'd be left with a permanently broken row — a URL inserted but with no usable short code, unreachable and not automatically cleaned up.

**Q: What does marking the lookup transaction read-only do?**
A: Hints to Hibernate/the database driver that no writes will happen, allowing minor optimizations (e.g., skipping dirty-checking). It's not required for correctness, just an optimization.

## 8. API design

**Q: Why `302` instead of `301` for the redirect?**
A: `301` is permanent and heavily cacheable by browsers/CDNs, meaning the server might never see the request again — breaking future click tracking or destination changes. `302` ensures every visit still hits the server.

**Q: Why return a DTO instead of the JPA entity directly?**
A: Decouples the API contract from the database schema — internal fields can change without breaking API consumers, and it avoids leaking internal details like the raw row ID.

**Q: How is invalid input handled?**
A: Bean validation (`@NotBlank`, `@URL`) rejects malformed requests before they reach business logic; a global exception handler converts validation failures into a consistent JSON `400` response.

## 9. Error handling

**Q: What happens if someone requests a short code that doesn't exist?**
A: A custom exception is thrown, caught centrally by a global exception handler, and converted into a `404` response with a JSON error body.

**Q: Why centralize exception handling instead of try/catch in each controller method?**
A: Keeps controllers focused purely on HTTP binding, ensures a consistent error response shape across all endpoints, and avoids duplicating error-formatting logic.

## 10. Testing

**Q: What's the test coverage like?**
A: Honest answer — currently just a context-load smoke test; no unit or integration tests written yet. Good things to mention proactively: unit tests for the Base62 encoder (edge cases like 0 and large values), service tests that mock the repository, and slice tests for the controller and repository layers (the dependencies for exactly this are already present in the build). Note the test suite runs against H2 with caching disabled (`spring.cache.type=none`), specifically so tests don't require Docker/Postgres/Redis to be running.

## 11. What would you do differently / what's next?

- Randomize short codes to remove guessability.
- Add cache eviction/invalidation if the data model ever allows editing or deleting a short URL.
- Add graceful degradation for a Redis outage (fall through to direct DB reads).
- Add negative caching for repeated lookups of nonexistent codes.
- Add rate limiting to prevent abuse of the create endpoint.
- Add authentication if users should only manage their own links.
- Add tests across all layers.

**Tip:** the strongest impression comes from the design-decision questions (sections 2–7) — that's where you're tested on understanding *why* the code is shaped the way it is, not just that it works. Sections 10–11 are also good to raise proactively, since naming known gaps yourself reads as self-awareness rather than a weakness.
