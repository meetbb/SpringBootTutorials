# JpaRepository — Deep Dive Notes

---

## Q1: `ShortUrlRepository` is just an interface with no implementing class anywhere in the codebase. So what object actually runs when I call `shortUrlRepository.findByShortCode("cb")`?

```java
public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {
    Optional<ShortUrl> findByShortCode(String shortCode);
}
```

### The big picture (remember this one sentence)

**Spring never gives you your interface — it gives you a fake object,
built at startup, that pretends to be your interface but secretly
turns every method call into a database query.**

That fake object is called a **proxy**.

### Why does this even work? (the problem it solves)

Normally in Java, if you want an object, you need a `class` that
`implements` the interface — otherwise you can't create it. You wrote
zero implementation. So logically, *something else* must be creating
an object that satisfies the `ShortUrlRepository` interface for
Spring to hand you.

Writing that implementation by hand (a class with a `findByShortCode`
method containing raw JDBC/SQL code) is exactly what Spring Data JPA
is saving you from. That's the whole point of the library.

### What actually happens, step by step

1. **At application startup**, Spring scans your code and finds
   `ShortUrlRepository extends JpaRepository<...>`.
2. Spring does **not** look for a class that implements it. Instead,
   it generates a brand-new object *in memory, at runtime* — a
   **dynamic proxy** — that implements `ShortUrlRepository` for you.
3. This proxy is backed by a real, generic class from Spring Data JPA
   called `SimpleJpaRepository`. Think of `SimpleJpaRepository` as a
   "universal repository" that already knows how to do `save()`,
   `findById()`, `delete()`, etc. for *any* entity — it just needs to
   be told which entity (`ShortUrl`) and which ID type (`Long`).
   That's exactly what `<ShortUrl, Long>` in
   `JpaRepository<ShortUrl, Long>` tells it.
4. That proxy object is registered as a Spring **bean**.
5. When your `ShortUrlService` asks for a `ShortUrlRepository` via
   constructor injection, Spring hands it this proxy — not "nothing,"
   not an error, but a fully working fake.

### So what happens for a method Spring didn't even know about?

`save()`, `findById()`, `delete()` are declared on `JpaRepository`
itself, so `SimpleJpaRepository` already has real code for them.

But `findByShortCode(String shortCode)` is a method **you** added —
it doesn't exist on `SimpleJpaRepository`. This is the interesting
part:

- When you call `findByShortCode("cb")`, the proxy intercepts the
  call.
- It doesn't run "your" method (there is no method body — you never
  wrote one).
- Instead, at **startup**, Spring already parsed the method's *name*
  (`findBy` + `ShortCode`) and pre-built a JPQL query from it, roughly:
  `SELECT s FROM ShortUrl s WHERE s.shortCode = :shortCode`.
- At call time, the proxy just runs that pre-built query with `"cb"`
  as the parameter, and hands you back the result wrapped in
  `Optional`.

This is why misspelling the method name (e.g. `findByShrotCode`)
fails at **startup**, not at compile time — Spring can't compile Java
method names against your entity fields (Java doesn't know your
database schema), but it *does* validate them the moment it tries to
build the proxy, which is early enough to catch the bug before any
request comes in.

### One-line mental model to keep forever

> `JpaRepository` doesn't hand you real code — it hands you a
> **contract** (the interface) and a **generated stand-in** that
> fulfills that contract by talking to Hibernate/the database on your
> behalf. You never see the stand-in's source code because it doesn't
> live in your project — it's built in memory, once, at startup.

### Where this leads next (don't chase these yet, just note them)

- *Who* actually builds this proxy — plain JDK dynamic proxies, or
  CGLIB? (Framework-internal detail — not needed for the big
  picture.)
- What happens underneath `SimpleJpaRepository.save()` — this is
  where Hibernate's "persistence context" comes in, which connects to
  your two-step save in `ShortUrlService.createShortUrl`.

---

## Q2: How does Spring turn the method *name* `findByShortCode` into a real SQL query — with no method body, no annotation, nothing?

```java
Optional<ShortUrl> findByShortCode(String shortCode);
```

### The big picture (remember this one sentence)

**Spring reads your method name like a sentence, splits it into
known keywords, and mechanically translates it into a query — the
same way every time, with no "understanding" involved.**

This is called a **derived query method** — "derived" because the
query is derived (worked out) from the method's name, not written by
you.

### Why does this even work? (the problem it solves)

Without this feature, every simple lookup would need either hand-written
SQL or a `@Query` annotation:

```java
@Query("SELECT s FROM ShortUrl s WHERE s.shortCode = :shortCode")
Optional<ShortUrl> findByShortCode(@Param("shortCode") String shortCode);
```

That's not wrong — but for a lookup this simple, it's pure
repetition: the method name already says everything the query needs
to say. Spring Data JPA's bet is: *if the name is unambiguous, let
the name be the query.*

### What actually happens, step by step

1. **At startup**, when Spring builds the proxy for
   `ShortUrlRepository` (see Q1), it also inspects every method that
   isn't already defined on `JpaRepository` — like `findByShortCode`.
2. It splits the method name into parts using known keywords:
   - `find` → this is a **query** method (other keywords: `get`,
     `read`, `count`, `exists`, `delete` — they all mean something
     different).
   - `By` → marks "here comes the filter condition."
   - `ShortCode` → this must match a **field on the `ShortUrl`
     entity**, case-insensitively matched to `shortCode`.
3. Spring checks that `ShortUrl` really has a field called
   `shortCode` (via reflection on the entity class). If it doesn't
   exist, **startup fails immediately** with an error like
   `No property 'shrotCode' found for type 'ShortUrl'` — this is the
   validation mentioned in Q1.
4. Spring then builds a JPQL query (JPQL = "SQL, but written against
   entity class/field names instead of table/column names"):
   ```
   SELECT s FROM ShortUrl s WHERE s.shortCode = ?1
   ```
5. This JPQL is stored on the proxy, parameterized and ready to run —
   it is **not** rebuilt on every call. The parsing in step 2–4 only
   happens once, at startup.
6. **At call time** (`findByShortCode("cb")`), the proxy just plugs
   `"cb"` into the already-built query and asks Hibernate to run it.
7. Hibernate translates that JPQL into real SQL for whatever database
   is configured. In this project that's two different translations
   depending on when you run it:
   - **Postgres** (via `docker-compose.yml`, normal app run):
     `SELECT * FROM short_urls WHERE short_code=?`
   - **H2** (`src/test/resources/application.properties`, during
     tests): same JPQL, different SQL dialect under the hood — this is
     the whole point of JPQL: **one query definition, many possible
     databases.**
   You can watch the real SQL yourself: `spring.jpa.show-sql=true` is
   already set in `application.properties`, so every query — including
   this generated one — prints to the console when you run the app.

### The naming rule, stripped to its essence

`findBy<FieldName>` → `WHERE <fieldName> = ?`

Everything else (`findByShortCodeAndCreatedAtAfter`,
`findByOriginalUrlContaining`, etc.) is the same mechanical
translation with more keywords chained on (`And`, `After`,
`Containing`, ...). You don't need to memorize the full keyword list —
just recognize that they all reduce to *"this word maps to a specific
piece of SQL, always the same way."*

### Why does misspelling fail at startup, not compile time?

Because `findByShrotCode` is still a syntactically valid Java method
name — the Java compiler has no idea it's supposed to match a field
on `ShortUrl`. The mismatch is only caught when *Spring* parses the
name and checks it against the entity's real fields — which happens
while building the proxy, i.e. at application startup. That's still
much better than finding out at runtime, mid-request, in production.

### One-line mental model to keep forever

> A derived query method isn't magic per-call — it's a **fixed
> translation performed once, at startup**. The method name is parsed
> exactly once into a real query; every call afterward just supplies
> parameters to that already-built query.

### Where this leads next (don't chase these yet, just note them)

- What happens when the naming pattern gets too complex to read
  (e.g. more than 2–3 chained conditions) — that's usually the signal
  to switch to `@Query` for readability, not because derived methods
  stop working.
- How JPQL differs from native SQL, and when you'd need
  `@Query(nativeQuery = true)` instead.

---

## Q3: Why does `findByShortCode` return `Optional<ShortUrl>` instead of just `ShortUrl` (or `null`)?

```java
Optional<ShortUrl> findByShortCode(String shortCode);
```

```java
// ShortUrlService.getByShortCode
return shortUrlRepository.findByShortCode(shortCode)
        .orElseThrow(() -> new ShortUrlNotFoundException(shortCode));
```

### The big picture (remember this one sentence)

**`Optional` forces the caller to explicitly decide what "not found"
means — it makes the missing case impossible to silently forget.**

### Why does this even work? (the problem it solves)

If `findByShortCode` returned a plain `ShortUrl` and no row matched,
Spring would have to return `null`. Nothing about a `ShortUrl`
variable's type tells you it might be `null` — so it's easy to write
`result.getOriginalUrl()` and blow up with a `NullPointerException`
weeks later, on a code path nobody thought to test.

`Optional<ShortUrl>` is a *wrapper box* that is honest in its type:
it might contain a `ShortUrl`, or it might be empty — and the
compiler won't let you reach inside without acknowledging that.

### What actually happens, step by step

1. The proxy runs the derived query from Q2:
   `SELECT s FROM ShortUrl s WHERE s.shortCode = ?1`.
2. Either a row matches, or it doesn't. Hibernate hands back one
   `ShortUrl` object, or nothing.
3. Spring Data JPA wraps that result: `Optional.of(shortUrl)` if
   found, `Optional.empty()` if not — this wrapping is automatic
   because the method's declared return type is `Optional<ShortUrl>`.
4. Your service code (`ShortUrlService.getByShortCode`) receives that
   `Optional` and must explicitly unwrap it. Here, `.orElseThrow(...)`
   says: "if it's empty, don't hand me a fake/null value — throw
   `ShortUrlNotFoundException` instead."
5. That exception is what eventually becomes the `404` response (see
   `END_TO_END_FLOWS.md`, redirect flow, Step D).

### What would go wrong without `Optional`?

If the method signature were `ShortUrl findByShortCode(...)`, a
not-found lookup would return `null`. `ShortUrlService` would then
need a manual `if (result == null) throw ...` check — and it's the
kind of check a developer can forget to write, because nothing in the
`ShortUrl` type forces you to. `Optional` moves that check from "a
convention you have to remember" to "a compiler-enforced step."

### One-line mental model to keep forever

> `Optional<T>` isn't about avoiding `null` — it's about making the
> *possibility* of "nothing here" visible in the method's type, so the
> caller is forced to handle it on purpose instead of by accident.

### Where this leads next (don't chase these yet, just note them)

- Why `@Cacheable` sits on `getByShortCode` (which returns `ShortUrl`,
  not `Optional`) and not directly on the repository method — how do
  exceptions thrown from `.orElseThrow(...)` interact with caching?
  (Already partly answered in `END_TO_END_FLOWS.md`: exceptions are
  never cached.)
- Other ways Java represents "value or absence" (`null`, exceptions,
  `Optional`) and when each is the right tool.

---

## Q4: `JpaRepository.save()` is transactional even though you never wrote `@Transactional` on the repository. So where does that transaction actually come from — and why did you still add `@Transactional` explicitly on `ShortUrlService.createShortUrl`?

```java
@Transactional
public ShortUrl createShortUrl(String originalUrl) {
    ShortUrl shortUrl = new ShortUrl();
    shortUrl.setOriginalUrl(originalUrl);

    ShortUrl saved = shortUrlRepository.save(shortUrl);
    saved.setShortCode(Base62Encoder.encode(saved.getId()));

    return shortUrlRepository.save(saved);
}
```

### The big picture (remember this one sentence)

**`SimpleJpaRepository` (the class behind the proxy from Q1) already
has `@Transactional` baked into its own methods — but that only
covers *one* repository call at a time; your explicit `@Transactional`
is what stretches the transaction across *both* `save()` calls so
they succeed or fail together.**

### Why does this even work? (the problem it solves)

A "transaction" is just a promise: everything inside it commits
together, or nothing does. `createShortUrl` does **two** separate
`save()` calls. If the first `save()` (no short code yet) committed
on its own, and the process crashed before the second `save()` ran,
you'd be left with a real database row that has a `NULL` short code
forever — a half-finished write, visible to everyone else querying
the table.

Wrapping both calls in one transaction is what guarantees "both
writes happen, or neither does" — which is exactly the comment
already sitting above this method in your code.

### What actually happens, step by step

1. **`SimpleJpaRepository` already has its own transactions.** If you
   open Spring Data JPA's source for `SimpleJpaRepository`, its
   `save()` method is annotated `@Transactional`. This is why calling
   `shortUrlRepository.save(x)` alone, with nothing else around it,
   still safely commits or rolls back — Spring wraps that single call
   in its own tiny transaction automatically.
2. **But each `@Transactional` on `save()` only covers that one
   call.** Left alone, your first `save(shortUrl)` would commit
   completely (row inserted, ID assigned, short code still null)
   *before* your second `save(saved)` even started. Two separate
   transactions, two separate commit points — exactly the crash
   scenario above becomes possible.
3. **Your explicit `@Transactional` on `createShortUrl` changes this.**
   When a method carrying `@Transactional` calls another
   `@Transactional` method (like `save()`), Spring's default behavior
   (`Propagation.REQUIRED`) is: *"if a transaction is already running,
   join it instead of starting a new one."*
4. So at runtime: calling `createShortUrl` opens **one** outer
   transaction. Both inner `save()` calls detect that a transaction is
   already active and simply join it rather than opening their own.
   Only when `createShortUrl` returns normally does everything commit
   — as one atomic unit.
5. If anything throws an unchecked exception partway through (e.g.
   `Base62Encoder.encode` blew up), the *entire* outer transaction
   rolls back — including the first `save()` — as if neither write had
   ever happened.

### How does Spring even intercept a plain method call to "start a transaction"? (ties back to Q1)

The exact same mechanism as Q1: `ShortUrlService` is itself wrapped in
a Spring-generated **proxy** because it's a `@Service` bean with
`@Transactional` methods. Calling `createShortUrl()` from outside
(e.g. from the controller) actually calls the proxy first, which opens
a database transaction, then calls your real method body, then commits
or rolls back based on whether it threw. `@Transactional` is not
magic syntax the JVM understands — it only works *through* a proxy,
exactly like the repository interface does.

### One-line mental model to keep forever

> Every `@Transactional` method call goes through a proxy that opens
> a transaction *if one isn't already running*, or joins the existing
> one if it is. Your explicit `@Transactional` on `createShortUrl`
> exists purely to make the two `save()` calls share one transaction
> instead of getting one each.

### Where this leads next (don't chase these yet, just note them)

- What "join an existing transaction" really means at the JDBC
  connection level — same connection, same in-progress `COMMIT`.
- Why calling a `@Transactional` method **from within the same class**
  (self-invocation) silently skips the proxy and does *not* start a
  new transaction — a classic Spring gotcha, not relevant here since
  `ShortUrlService` calls `shortUrlRepository`, a different bean.
- Hibernate's persistence context / first-level cache, and whether
  your two `save()` calls really trigger two `INSERT` statements or
  get batched — this is the natural next stop after transactions.

---

## Q5: Inside one transaction, `createShortUrl` calls `save()` twice.
Does that mean two separate round-trips to the database happen right
away, or does Hibernate wait and batch them somehow?

```java
@Transactional
public ShortUrl createShortUrl(String originalUrl) {
    ShortUrl shortUrl = new ShortUrl();
    shortUrl.setOriginalUrl(originalUrl);

    ShortUrl saved = shortUrlRepository.save(shortUrl);      // save #1
    saved.setShortCode(Base62Encoder.encode(saved.getId()));
    return shortUrlRepository.save(saved);                   // save #2
}
```

### The big picture (remember this one sentence)

**Inside a transaction, Hibernate keeps a scratchpad called the
"persistence context" that tracks every entity you've touched — and
your entity's `@GeneratedValue(strategy = GenerationType.IDENTITY)`
setting is exactly what forces `save()` #1 to hit the database
immediately, instead of waiting.**

### Why does this even work? (the problem it solves)

Hibernate doesn't want to run a `SELECT`/`INSERT`/`UPDATE` for every
tiny change you make to an entity — that would be slow. So for the
lifetime of one transaction, it keeps an in-memory map of
"entity → its current state" called the **persistence context**
(also called the **first-level cache**). Changes accumulate there,
and Hibernate decides *when* to actually send SQL to the database —
usually as late as possible (this is called **write-behind**), or
right when you need something only the database can give you.

### What actually happens, step by step

1. `save(shortUrl)` (save #1) is called on a brand-new `ShortUrl` with
   no `id` yet. Hibernate needs to insert it, so it hands off an
   `INSERT` — but here's the key detail: your entity uses
   `@GeneratedValue(strategy = GenerationType.IDENTITY)`, which means
   **the database itself** (Postgres's auto-increment column) decides
   the `id`, not Hibernate. Hibernate has no way to know the `id`
   without actually running the `INSERT` right now. So `IDENTITY`
   strategy forces this `INSERT` to execute **immediately**, not
   later — there's no room for write-behind here.
2. The database returns the assigned `id` (e.g. `125`). Hibernate puts
   this now-fully-formed entity into the persistence context, tagged
   as **managed**.
3. Your code reads `saved.getId()` (`125`), computes the short code,
   and calls `saved.setShortCode(...)`.
4. `save(saved)` (save #2) is called on an entity that **already has
   an `id`**. Hibernate recognizes this isn't a new entity — it's an
   update to a managed one. It issues an `UPDATE short_urls SET
   short_code = ... WHERE id = 125`.
5. Both of these SQL statements happened inside the **same
   transaction** (from Q4), so they only become permanent together
   when the transaction commits at the end of `createShortUrl`.

So in this specific case, the answer is: **no batching happens** —
you genuinely get two round-trips (`INSERT` then `UPDATE`), because
`IDENTITY` generation strategy requires knowing the real `id` before
you can even build the short code — a known, deliberate trade-off in
this project ("two-step insert doubles write load per creation").
Now you know the precise mechanical reason why it's unavoidable with
this ID strategy.

### What is the persistence context actually *for*, if not batching here?

Its main day-to-day value is avoiding **duplicate reads**: if you
called `findByShortCode("cb")` twice within the same transaction,
the second call wouldn't need to hit the database again — Hibernate
would recognize it already has that exact entity (by ID) in the
persistence context and hand you the same in-memory object. (This is
different from the Redis cache in `ShortUrlService` — that one spans
*across* requests/transactions; the persistence context only lives
for the duration of *one* transaction.)

### One-line mental model to keep forever

> The persistence context is a **per-transaction scratchpad** that
> lets Hibernate delay/skip redundant SQL — except when something
> forces it to act immediately, like needing a database-generated ID
> before your own code can proceed.

### Where this leads next (don't chase these yet, just note them)

- What "managed," "detached," and the other entity states mean
  precisely — Q6, next.
- What would change if `GenerationType.SEQUENCE` or `TABLE` were used
  instead of `IDENTITY` (some of those *can* be batched/pre-allocated,
  avoiding the immediate round-trip).

---

## Q6: The terms "managed," "detached," "transient," and "removed" keep coming up around JPA entities. What do they actually mean, and which one is `ShortUrl` in at each point of `createShortUrl`?

### The big picture (remember this one sentence)

**An entity's "state" just answers one question: is Hibernate
currently watching this object for changes, and if so, is it still
attached to a live transaction?**

### Why does this even work? (the problem it solves)

Hibernate can only auto-detect and auto-save your changes (e.g. that
`UPDATE` in Q5, which you never explicitly asked for beyond calling
`save()`) for objects it is actively "watching." It needs a clear rule
for which objects that applies to — otherwise it would have to guess.
The four states are that rule, made explicit.

### The four states, traced through your actual method

```java
ShortUrl shortUrl = new ShortUrl();              // state: TRANSIENT
shortUrl.setOriginalUrl(originalUrl);            // still TRANSIENT

ShortUrl saved = shortUrlRepository.save(shortUrl); // now: MANAGED
saved.setShortCode(Base62Encoder.encode(saved.getId())); // still MANAGED

return shortUrlRepository.save(saved);           // still MANAGED, until commit
```

1. **Transient** — `new ShortUrl()` just created a plain Java object.
   Hibernate has never heard of it. Nothing you do to it (setting
   `originalUrl`) is tracked or will ever reach the database on its
   own. It's just an object, like any other Java object.
2. **Managed** — the moment `save()` succeeds, Hibernate starts
   tracking this exact object inside the persistence context (Q5)
   *for the rest of the current transaction*. From this point,
   Hibernate knows this object's database identity (`id = 125`) and
   watches its fields. This is why calling `.setShortCode(...)`
   directly on `saved` — with no second explicit `save()` call even
   needed, in some setups — can still end up as an `UPDATE`: Hibernate
   compares the managed entity's current field values against what it
   remembers from the last flush, and generates SQL for the
   difference. (Your code *does* still call `save()` again explicitly
   — which is fine and makes the intent clearer, but strictly speaking
   the managed-entity "dirty checking" mechanism is what would make it
   work even without the second call.)
3. **Detached** — once the transaction commits (i.e., `createShortUrl`
   returns and the `@Transactional` proxy from Q4 closes the
   transaction), Hibernate stops watching this entity. The `ShortUrl`
   object the controller now holds still has real data in it (`id`,
   `shortCode`, etc.) — but it's just a plain object again. If you
   changed `saved.setOriginalUrl("something else")` *after* this
   point, nothing would happen to the database — no one is watching.
4. **Removed** — not used anywhere in this project yet (there's no
   delete feature), but for completeness: this is the state an entity
   enters after you call `repository.delete(entity)` within a
   transaction — Hibernate now plans to issue a `DELETE` for it at
   flush/commit time.

### One-line mental model to keep forever

> Transient = Hibernate doesn't know it exists. Managed = Hibernate is
> actively watching it and will auto-generate SQL for any changes,
> but only until the transaction ends. Detached = the watching has
> stopped; it's a plain object again, even though it still has real
> database values in it.

### Where this leads next (don't chase these yet, just note them)

- What happens if you try to modify a **detached** entity and call
  `save()` on it again from a *different* transaction — this is
  `merge()` territory, and matters the moment you add an "edit
  destination" feature (which would also need cache invalidation,
  since Redis caching currently assumes short URLs never change).

---

## Q7: `ShortUrl` currently has no relationships to other entities. If one were added later (e.g. a `User` who owns a short URL), what's the "N+1 query problem," and how would `JpaRepository` make it worse without you noticing?

### The big picture (remember this one sentence)

**Fetching a list of entities that each lazily load a related entity
can silently turn "1 query" into "1 + N queries" — one extra query
per row — and `JpaRepository`'s defaults make this the easy path, not
the safe path.**

### Why does this even work? (the problem it solves — a hypothetical extension of your project)

Imagine `ShortUrl` grew a field like:

```java
@ManyToOne(fetch = FetchType.LAZY)
private User createdBy;
```

`FetchType.LAZY` means: "don't load the related `User` from the
database until someone actually calls `.getCreatedBy()`." This sounds
efficient — why load data nobody asked for? But it creates a trap.

### What actually happens, step by step (the trap)

1. You call `shortUrlRepository.findAll()` to list, say, 100 short
   URLs. This runs **one** `SELECT * FROM short_urls` — fast, single
   round-trip.
2. Each returned `ShortUrl` is a Hibernate **proxy** for its
   `createdBy` field — not an actual loaded `User`, just a stand-in
   that knows *how* to fetch the real one if asked (same proxy idea as
   Q1, just applied to a single field instead of a whole repository).
3. Now suppose you loop over all 100 results and call
   `shortUrl.getCreatedBy().getName()` for each one (e.g. to show
   "created by Alice" in a UI). Each call to `.getCreatedBy()` on a
   *different* `ShortUrl` triggers **a brand-new `SELECT * FROM users
   WHERE id = ?`** — because each `User` proxy is unloaded and
   independent.
4. Result: **1** query to fetch the short URLs, plus **100** more
   queries (one per row) to fetch each one's `createdBy`. That's the
   "N+1" — 1 initial query, N follow-up queries, where N is the number
   of rows.

### Why does `JpaRepository` make this easy to miss?

Nothing about calling `findAll()` or looping over the result *looks*
wrong. There's no visible sign that you triggered 100 extra queries —
the code compiles, runs, and returns correct data. You'd typically
only notice via slow response times in production, or by explicitly
watching SQL logs (`spring.jpa.show-sql=true`, already enabled in this
project) and counting how many `SELECT` statements print for one
request.

### How would you avoid it, if this came up?

Two common fixes (just to know they exist, not to implement now):
`JOIN FETCH` in a custom `@Query` (loads both entities in one SQL
join), or `@EntityGraph` on the repository method (declaratively says
"eagerly load this relationship for this specific query").

### One-line mental model to keep forever

> Lazy loading defers a query per relationship, per row. It's invisible
> in the code and only shows up as "mysteriously many queries" in the
> logs — always count actual SQL statements when a list endpoint
> feels slow, don't just trust that "it's only one repository call."

### Where this leads next (don't chase these yet, just note them)

- This project has no relationships yet, so this is purely
  preparation — revisit for real the day `ShortUrl` gains a
  `@ManyToOne`/`@OneToMany` field.

---

## Q8: `JpaRepository` is a Spring Data abstraction over the JPA *specification* — but Postgres has no idea what "JPA" is. What is the actual chain of translation from `shortUrlRepository.save(...)` down to real SQL over the wire?

### The big picture (remember this one sentence)

**JPA is just a rulebook (an interface spec) with no code of its
own; Hibernate is the one library actually doing the work, and JDBC
is the one standard both of them ultimately speak to the database
in.**

### Why does this even work? (the problem it solves)

Without this layering, switching databases (Postgres → MySQL → H2,
exactly like this project does between production and tests) would
mean rewriting your data-access code. Each layer exists to hide a
specific kind of detail from the layer above it:

- **JPA** hides *which persistence provider* you use.
- **Hibernate** hides *which specific SQL dialect* your database
  speaks.
- **JDBC** hides *which database vendor's wire protocol* is used.

### The full chain, traced through your project

```
shortUrlRepository.save(shortUrl)
        │
        ▼
Spring Data JPA proxy (SimpleJpaRepository) — see Q1
        │  translates the call into JPA-standard operations
        ▼
JPA (jakarta.persistence.*) — just an interface/annotation spec
(@Entity, @Id, EntityManager, ...) — defines *what* must happen,
not *how*. JPA itself has no runnable implementation.
        │
        ▼
Hibernate — the actual JPA "provider" used in this project
(pulled in transitively by spring-boot-starter-data-jpa).
Reads your @Entity/@Column/@GeneratedValue annotations on ShortUrl
and generates real SQL text: e.g.
"insert into short_urls (original_url, created_at) values (?, ?)"
        │
        ▼
JDBC (java.sql.*) — the standard Java API for "send this SQL string
and these parameters to a database, give me back rows/results."
Hibernate calls JDBC; it doesn't talk to sockets directly.
        │
        ▼
The vendor-specific JDBC Driver — this project depends on the
`postgresql` driver for production, and H2's own driver for tests.
This driver is the only piece that actually knows Postgres's/H2's
real network wire protocol.
        │
        ▼
The actual database process (Postgres, or H2 in-memory for tests)
```

### Why this project can run on two different databases without code changes

This entire chain is exactly why `application.properties` (Postgres)
and `src/test/resources/application.properties` (H2) can point at
completely different databases while `ShortUrlRepository`,
`ShortUrlService`, and `ShortUrl` stay untouched: only the bottom two
links of the chain (the SQL dialect Hibernate generates, and the JDBC
driver used) change based on configuration. Everything above that —
your annotations, your repository interface, your service code — is
written against JPA's abstractions, not against Postgres or H2
specifically.

### One-line mental model to keep forever

> JPA = the contract. Hibernate = the one actually fulfilling it.
> JDBC = the universal language Hibernate uses to talk to whichever
> database is configured. Swapping the database only ever means
> swapping the bottom of this chain, never the top.

### Where this leads next (don't chase these yet, just note them)

- What `spring.jpa.hibernate.ddl-auto=update` (already set in
  `application.properties`) actually does at this layer — it's a
  Hibernate feature, not a JPA-spec feature, that auto-generates
  `CREATE TABLE`/`ALTER TABLE` SQL from your `@Entity` classes.
- Connection pooling (HikariCP, Spring Boot's default) — sits between
  JDBC and the driver, reusing connections instead of opening a new
  one per query. Not yet relevant at this project's scale, but a
  standard next question in interviews about this exact chain.
