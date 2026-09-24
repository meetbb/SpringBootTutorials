# RedisCacheConfig — Deep Dive Notes

---

## Q1: What is Spring even trying to do here, and why does it need this class at all?

```java
@Configuration
public class RedisCacheConfig {

    @Bean
    public RedisCacheManagerBuilderCustomizer redisCacheManagerBuilderCustomizer() {
        GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer.builder()
                .enableUnsafeDefaultTyping()
                .build();

        return builder -> builder.cacheDefaults(
                RedisCacheConfiguration.defaultCacheConfig()
                        .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer)));
    }
}
```

### The big picture (remember this one sentence)

**Spring Boot already builds a `RedisCacheManager` for you automatically
— this class doesn't replace that, it just hands Spring one small
instruction to fold into the manager it was going to build anyway.**

### Why does this even work? (the problem it solves)

When `ShortUrlService.getByShortCode` runs with `@Cacheable(value =
"shortUrls", ...)`, *something* has to exist that knows how to talk to
Redis: open a connection, decide a key name, turn your `ShortUrl`
object into something Redis can store, and later read it back out.
You never wrote that "something" yourself. Spring Boot's autoconfiguration
sees `spring-boot-starter-data-redis` + `spring-boot-cache` on the
classpath and builds a `RedisCacheManager` bean for you at startup,
with sensible defaults.

The problem is: the *defaults* don't fit this project (that's Q2). You
need to tweak *one* setting on that auto-built manager — but you never
constructed it yourself, so you can't just call `.setSerializer(...)`
on it like a normal object you own.

### What actually happens, step by step

1. **At application startup**, Spring's autoconfiguration machinery
   decides to build a `RedisCacheManager` bean, because Redis and
   caching are both on the classpath and caching is enabled
   (`@EnableCaching`, or Boot's own auto-detection of `@Cacheable`
   usage).
2. **Before finalizing that manager**, Boot deliberately pauses and
   asks: "does the application context contain any beans of type
   `RedisCacheManagerBuilderCustomizer`?" This is a named *extension
   point* — a hook Spring Boot built on purpose so you can influence
   an auto-configured object without having to construct the whole
   thing yourself.
3. **`@Configuration` is what makes this class visible to Spring in
   the first place.** It's under the same package tree as
   `UrlShortenerApplication` (the `@SpringBootApplication` entry
   point), so component scanning finds it, and `@Configuration` tells
   Spring "treat every `@Bean` method in this class as a bean
   definition to register."
4. **The `@Bean` method returns a lambda**, not a finished object.
   Its return type, `RedisCacheManagerBuilderCustomizer`, is a
   functional interface with one method: given a `builder`, do
   something to it. So this bean isn't "the cache manager" — it's
   "one small edit to apply to the cache manager's builder before it
   gets built."
5. **Spring Boot finds this bean and calls it**, passing in the
   builder it's about to use to construct the real
   `RedisCacheManager`. Your lambda calls `.cacheDefaults(...)` on
   that builder, changing what every cache backed by Redis will use
   by default (more on what, in Q2).
6. **Only after all customizers have run** does Spring Boot actually
   build the final `RedisCacheManager` and register *that* as the
   bean your `@Cacheable` annotations end up using.

### Why not just write the whole `RedisCacheManager` yourself?

You could — Spring lets you define your own `@Bean
RedisCacheManager` and skip autoconfiguration entirely. But then
you'd own *everything*: connection factory wiring, default TTL,
per-cache overrides, all of it — most of which Boot's defaults
already get right for this project (see `application.properties`'s
`spring.cache.redis.time-to-live`). The customizer hook exists
specifically so you can change *one thing* (serialization) without
throwing away everything else Boot does correctly for free.

### One-line mental model to keep forever

> `RedisCacheConfig` doesn't create the cache — it registers a small,
> named "patch" that Spring Boot automatically applies while building
> the cache manager it was already going to build. You're not
> replacing Spring's work, you're intercepting one step of it.

### Where this leads next (don't chase these yet, just note them)

- What `@EnableCaching` does, and where it's actually declared in this
  project.
- What other `*Customizer` hooks Spring Boot exposes for other
  auto-configured beans (this pattern — "give me a bean that edits the
  builder" — repeats across Spring Boot, not just for Redis caching).

---

## Q2: What problem is serialization solving, and why does the default break for `ShortUrl`?

```java
@Cacheable(value = "shortUrls", key = "#shortCode")
@Transactional(readOnly = true)
public ShortUrl getByShortCode(String shortCode) {
    return shortUrlRepository.findByShortCode(shortCode)
            .orElseThrow(() -> new ShortUrlNotFoundException(shortCode));
}
```

### The big picture (remember this one sentence)

**Redis only knows how to store bytes — never a Java object — so
something must convert your `ShortUrl` to bytes on the way in and back
to a `ShortUrl` on the way out, and Spring Boot's default converter
doesn't work for this particular class.**

### Why does this even work? (the problem it solves)

When `getByShortCode("cb")` runs and actually hits the database (a
cache miss), `@Cacheable` doesn't just return the result — it also
stores it in Redis under a key like `shortUrls::cb`, so the next call
with the same `shortCode` can skip the database entirely. Redis is a
separate process, reachable only over a network connection, and it
has no concept of a Java class. It stores raw bytes (or a few native
structures like strings and hashes) and nothing else. Turning a
`ShortUrl` object into bytes — and later turning those bytes back into
a `ShortUrl` — is called **serialization**, and *someone* has to
decide exactly how that conversion works.

### What actually happens, step by step

1. **Spring Boot's default answer to "how do I serialize a cached
   value?" is JDK serialization** — Java's built-in mechanism, the
   same one used for writing objects to files or across the network
   with `ObjectOutputStream`.
2. **JDK serialization has one hard requirement**: the class being
   serialized must implement `java.io.Serializable`. This is a
   marker interface — it adds no methods, it just tells the JVM "you
   are allowed to convert this class to a byte stream."
3. **`ShortUrl` does not implement `Serializable`** (per
   `PROJECT_CONTEXT.md`). So if `RedisCacheConfig` didn't exist, the
   very first time `getByShortCode` tried to *write* a `ShortUrl`
   into Redis, JDK serialization would fail at runtime with a
   `SerializationException` — not a compile error, since Java has no
   way to catch "this object might get serialized later" at compile
   time.
4. **This is exactly what `RedisCacheConfig` prevents.** Instead of
   letting Spring Boot use its default JDK serializer, the customizer
   swaps in `GenericJacksonJsonRedisSerializer` — a serializer that
   converts objects to/from **JSON text** instead of Java's binary
   serialization format. JSON conversion (via Jackson, the library
   Spring already uses for your REST API's request/response bodies)
   has no `Serializable` requirement at all — it just reads the
   object's fields via reflection/getters, the same way your
   controller's JSON responses already get built.
5. **Why JSON is also a deliberate, not just convenient, choice**: the
   code comment calls out that cached values stay "human-readable" —
   if you connect to Redis directly (e.g. `redis-cli GET
   shortUrls::cb`) and look at a cached entry, you'd see readable JSON
   text, not an opaque binary blob. That matters for debugging: you
   can literally read what's cached without writing any code.

### So who actually calls this serializer, and when?

You never call it yourself. It's wired into the `RedisCacheManager`
via the customizer from Q1. Every time `@Cacheable` needs to write a
value to Redis (cache miss) or read one back (cache hit), the
`RedisCacheManager` internally delegates to whatever serializer was
configured for that cache — which, because of `RedisCacheConfig`, is
this JSON serializer instead of the JDK default.

### One-line mental model to keep forever

> Serialization is the unavoidable translation step between "a Java
> object in memory" and "bytes Redis can store." Spring Boot picks a
> default translator (JDK serialization) that silently assumes every
> cached class implements `Serializable`. `ShortUrl` breaks that
> assumption, so `RedisCacheConfig` swaps in a different translator
> (JSON via Jackson) that doesn't need it.

### Where this leads next (don't chase these yet, just note them)

- What `enableUnsafeDefaultTyping()` is actually for, and why the word
  "unsafe" is in the method name — this is about the *read* side
  (turning JSON back into a real `ShortUrl` instead of a generic map),
  and is a separate concern from *why* JSON was chosen at all.
- What would happen on a cache read if default typing were left off —
  ties directly into `@Cacheable`'s return type contract on
  `getByShortCode`.
- Why the tests never exercise any of this (`spring.cache.type=none`
  in `src/test/resources/application.properties`) — meaning a bug here
  would only surface against a real, running Redis instance.

---

## Q3: What does a cached `ShortUrl` actually look like inside Redis, and what is the `@class` field doing there?

```java
GenericJacksonJsonRedisSerializer serializer = GenericJacksonJsonRedisSerializer.builder()
        .enableUnsafeDefaultTyping()
        .build();
```

### The big picture (remember this one sentence)

**Plain JSON only describes *data* — it has no way to say "and by the
way, this should become a `ShortUrl` object, not just a generic map"
— so `enableUnsafeDefaultTyping()` makes Jackson smuggle that type
information into the JSON itself.**

### Why does this even work? (the problem it solves)

Q2 established that a cache miss on `getByShortCode("cb")` writes a
`ShortUrl` into Redis as JSON. Something like:

```json
{"id":125,"shortCode":"cb","originalUrl":"https://example.com","createdAt":"2026-09-20T10:15:30"}
```

That's readable — but notice it's just field names and values. JSON,
as a format, has no built-in concept of "this object is a `ShortUrl`."
If Jackson read this JSON back with no extra information, its only
reasonable guess would be a generic `LinkedHashMap<String, Object>` —
because a plain JSON object *could* deserialize into any Java type
that has matching fields; nothing in the text picks one.

But `getByShortCode`'s return type is `ShortUrl`, not
`Map<String, Object>`. If a cache *hit* handed back a `LinkedHashMap`
instead, the very next line in `ShortUrlController` that calls
`.getOriginalUrl()` on it would fail — either a compile-time type
mismatch (if Spring tried to shove a `Map` where a `ShortUrl` is
expected) or a `ClassCastException` at runtime.

### What actually happens, step by step

1. **`enableUnsafeDefaultTyping()` turns on Jackson's "polymorphic type
   handling."** When *writing* a value to Redis, the serializer adds
   one extra field recording the value's real Java class:
   ```json
   {"@class":"com.subreathingcode.URLShortener.domain.ShortUrl","id":125,"shortCode":"cb","originalUrl":"https://example.com","createdAt":"2026-09-20T10:15:30"}
   ```
2. **On a cache hit**, when Spring's `RedisCacheManager` reads that
   entry back for `getByShortCode`, it hands the raw JSON to this same
   serializer to deserialize.
3. **The serializer reads `@class` first**, sees
   `com.subreathingcode.URLShortener.domain.ShortUrl`, and uses that
   to decide which Java class to instantiate — then maps the
   remaining fields (`id`, `shortCode`, `originalUrl`, `createdAt`)
   onto that class, exactly the way Jackson already does for your
   REST API's request bodies.
4. **The result**: `getByShortCode` gets back a real `ShortUrl`
   object on a cache hit — type-identical to what it would have gotten
   from the repository on a cache miss. The caller (`ShortUrlController`,
   or whatever calls the service) can't tell the difference between
   "this came from the database" and "this came from Redis."

### Why is this called "default typing" specifically?

Because it's a fallback behavior, not something you declare per
field. Normally, if you deserialize JSON into a *known, fixed* type
(e.g. `objectMapper.readValue(json, ShortUrl.class)`), you don't need
`@class` at all — you're telling Jackson the target type directly, up
front. Default typing exists for exactly this project's situation: the
`RedisCacheManager` is generic infrastructure that caches *any* type
for *any* `@Cacheable` method in the app — at the moment it writes
bytes to Redis, it has no idea in advance what Java type will be read
back later. Embedding `@class` is how the serializer make that
decision recoverable later, without the cache manager needing to know
your domain types.

### One-line mental model to keep forever

> Plain JSON round-trips *data*. Default typing makes the JSON also
> carry its own *type*, by embedding the fully-qualified class name
> as an extra field — which is the only way generic, reusable
> infrastructure like `RedisCacheManager` can hand you back the exact
> class you cached, instead of a generic map.

### Where this leads next (don't chase these yet, just note them)

- Why the method is named `enableUnsafe*` rather than just
  `enableDefaultTyping` — Q4, next.
- What would concretely break (compile error? runtime error? silent
  wrong data?) if this were turned off but JSON serialization stayed
  on.

---

## Q4: Why does the method have "unsafe" in its name — what's the actual risk, and why is it acceptable in this project?

```
Redis's enableUnsafeDefaultTyping() embeds a @class field in cached JSON
so values deserialize to the right type. This is safe only because Redis
is private infrastructure this app alone writes to — it would be a
deserialization risk if the cache were ever exposed to untrusted writers.
```

### The big picture (remember this one sentence)

**The `@class` field from Q3 isn't just data Jackson reads — it's an
instruction Jackson obeys ("go instantiate this class"), and obeying
instructions written by someone else is exactly how deserialization
vulnerabilities happen.**

### Why does this even work? (the problem it solves — and the danger it opens)

Think about what `@class` really means to Jackson at deserialization
time: "construct an instance of *this exact class name*, then populate
its fields from the rest of this JSON." That's a powerful thing to let
external data control. If an attacker could get *arbitrary* text into
that `@class` field — say, the name of some class already on your
classpath whose constructor or setters have dangerous side effects
(this is a well-known category of Java exploit called a
**deserialization gadget chain**) — they could potentially get code to
execute just by getting a crafted payload deserialized, with no other
access needed.

This is precisely why "default typing" isn't Jackson's default
behavior, and why the method to turn it on is spelled `enableUnsafe*`
instead of just `enableDefaultTyping` — the name itself is a warning
label, put there deliberately by the library authors.

### What actually happens, step by step (why it's fine *here*, specifically)

1. **The question that matters is: who can write to this Redis
   instance?** In this project, Redis runs
   locally via `docker-compose.yml`, and the *only* thing that ever
   writes to it is this Spring Boot application itself, through
   `@Cacheable` on `getByShortCode`.
2. **There is no code path where user-controlled input reaches Redis
   as raw bytes.** The `shortCode` from the URL path is used only as
   a *cache key* (`key = "#shortCode"`) — a string used to look values
   up — never as cache *content* that gets deserialized. The actual
   value stored is always a `ShortUrl` object your own service code
   built from a trusted database row.
3. **So the `@class` field in every cached entry is always one you
   wrote** — always `com.subreathingcode.URLShortener.domain.ShortUrl`,
   never anything an outside party chose. There's no attacker-controlled
   text anywhere near that field.
4. **The risk only appears if that assumption breaks** — e.g. if Redis
   were ever exposed on a network where an untrusted party could
   `SET` arbitrary keys/values directly (bypassing your application
   entirely), or if some future feature let user input flow into
   what gets cached without your code constructing the object first.
   Neither is true today.

### So "unsafe" doesn't mean "currently exploitable" here — what does it mean?

It means: this setting removes a safety net that exists specifically
*because* other applications get this wrong. In this project, the
precondition for the danger (untrusted writers reaching Redis) simply
isn't met — but the setting doesn't know that; it behaves identically
regardless of who's writing.

### One-line mental model to keep forever

> "Unsafe" describes what the *mechanism* allows, not whether *this
> specific setup* is currently exploitable. A setting is safe to use
> only as long as the precondition that makes it safe — here, "only
> this app ever writes to this Redis" — remains true. Document that
> precondition next to the setting, because the code itself gives no
> warning when the precondition stops holding.

### Where this leads next (don't chase these yet, just note them)

- What Jackson-recommended safer alternatives exist (e.g.
  `activateDefaultTyping` with an explicit allow-list of classes,
  rather than "trust any class name") — relevant the day Redis's
  trust boundary changes.
- Whether a shared, multi-service Redis deployment (common "at 10x
  scale") would require revisiting this setting even without any
  attacker involved — just because more of *your own* code might then
  write different types into overlapping cache namespaces.

---

## Q5: If `RedisCacheConfig` were deleted, what's the exact moment things would break — and why do the tests never catch it?

```java
@Cacheable(value = "shortUrls", key = "#shortCode")
@Transactional(readOnly = true)
public ShortUrl getByShortCode(String shortCode) { ... }
```

### The big picture (remember this one sentence)

**Deleting `RedisCacheConfig` breaks nothing until the exact instant a
real cache write happens against a real Redis — which is a runtime
event, not a startup check, and the test suite never triggers it at
all.**

### Why does this even work? (the problem it solves — a "what if" trace)

It's tempting to assume "config classes get validated at startup," the
way a missing bean or a bad `@Value` placeholder often does. This one
doesn't, because nothing about `RedisCacheConfig`'s *absence* is
invalid — Spring Boot just falls back to its own default
(JDK-serialization) `RedisCacheManager`, which is a perfectly valid
bean. The failure only exists in the gap between "what the default
serializer can handle" and "what `ShortUrl` actually is."

### What actually happens, step by step, if you deleted the file

1. **Compile time**: nothing breaks. `RedisCacheConfig` isn't imported
   or referenced by any other class — nothing depends on it by name
   (this is the observation that started this whole set of notes).
2. **Application startup**: nothing breaks. Spring Boot's Redis cache
   autoconfiguration still runs, still builds a working
   `RedisCacheManager` — just with its default JDK serializer instead
   of the JSON one. The app starts cleanly and logs no warnings.
3. **First `POST /api/urls`**: nothing breaks. `createShortUrl` never
   touches the cache at all (per `PROJECT_CONTEXT.md`: "Cache is not
   written on create — only populated lazily by the first read").
4. **First `GET /{shortCode}` for a given code (a cache miss)**:
   `getByShortCode` runs, hits the database, gets a real `ShortUrl`
   back — and returns it successfully. The request still works,
   because reading the entity doesn't require serialization.
5. **The instant `@Cacheable`'s advice tries to *store* that result
   into Redis** — after the method returns, before the response goes
   out — the default JDK serializer inspects `ShortUrl`, finds it does
   not implement `Serializable`, and throws (typically surfacing as a
   `SerializationException` wrapping a `NotSerializableException`).
   Depending on Spring's cache error handling, this could either
   propagate as a `500` to the actual HTTP caller, or (with a custom
   `CacheErrorHandler`, which this project doesn't have) be logged and
   swallowed while still returning the correct redirect. Either way,
   it is the *first real Redis write* — not startup — that surfaces
   the bug.

### Why the test suite would never catch this

`src/test/resources/application.properties` sets
`spring.cache.type=none` specifically so tests don't need a running
Redis. With caching disabled entirely, `@Cacheable` becomes a no-op —
`getByShortCode` just runs its body directly every time, no
serialization ever happens, no `RedisCacheManager` is even built. The
only current test (`contextLoads()`, per `PROJECT_CONTEXT.md`) doesn't
call `getByShortCode` at all, so even if caching *were* enabled in
tests, this specific test wouldn't reach the bug. A regression here
would only be caught by manually running the app against the real
Docker-composed Redis and hitting the redirect endpoint twice for the
same code.

### One-line mental model to keep forever

> "It compiles and the app starts" only proves the *shape* of your
> configuration is valid — it says nothing about whether the
> *behavior* it configures will work under real conditions. Some bugs
> are only observable at the exact moment a specific code path runs
> against a specific piece of real infrastructure — here, that's "a
> second request for the same short code, against a real Redis."

### Where this leads next (don't chase these yet, just note them)

- What a `CacheErrorHandler` is, and whether this project should have
  one so a Redis-layer failure degrades to "skip the cache, still
  serve the redirect" instead of failing the whole request — this
  connects to the open question in `PROJECT_CONTEXT.md` about Redis
  fallback behavior.
- What it would take to actually write a test that *does* catch this
  (e.g. `@SpringBootTest` with `spring.cache.type=redis` and
  Testcontainers running a real Redis) — and why that's a meaningfully
  heavier test than the current `contextLoads()` smoke test.

---

## Q6: Does `.cacheDefaults(...)` apply only to the `shortUrls` cache, or to every cache in the app — and what's the blast radius if that assumption is wrong?

```java
return builder -> builder.cacheDefaults(
        RedisCacheConfiguration.defaultCacheConfig()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer)));
```

### The big picture (remember this one sentence)

**`cacheDefaults(...)` sets the *fallback* configuration used by every
cache that doesn't get its own explicit override — today that's just
`shortUrls`, but the method itself has no idea how many caches exist
and would apply identically to all of them.**

### Why does this even work? (the problem it solves)

`RedisCacheManager.Builder` supports two levels of configuration:
- **`cacheDefaults(...)`** — "unless told otherwise, every cache uses
  this configuration" (TTL, serializer, key prefix, etc.).
- **`withCacheConfiguration(String cacheName, RedisCacheConfiguration
  config)`** — "this *specific* cache, by name, uses a different
  configuration than the default."

`RedisCacheConfig` only calls the first one. That's a scope decision,
whether or not it was made consciously: it says "I want *every* Redis
cache in this app to use JSON serialization with default typing,"
rather than "I want this specific setting only for `shortUrls`."

### What actually happens, step by step

1. Right now, this project has exactly **one** named cache:
   `"shortUrls"`, declared via `@Cacheable(value = "shortUrls", ...)`
   on `getByShortCode`. So today, "every cache" and "the shortUrls
   cache" are the same set — there's no observable difference yet.
2. If a second `@Cacheable(value = "someOtherCache")` were added
   anywhere else in the app — say, caching something unrelated to
   `ShortUrl` — it would **automatically** inherit this exact same
   JSON-with-default-typing serializer, with zero additional
   configuration. Nobody would need to remember to "also configure the
   new cache" — that's the entire point of `cacheDefaults`.
3. This is a reasonable default for *this* project (small, single
   team, every cached type is controlled by the same codebase) — but
   it's worth noticing it's a choice, not a law. A larger project with
   many caches holding very different kinds of data (some sensitive,
   some huge, some needing different TTLs) would often want per-cache
   overrides via `withCacheConfiguration(...)` instead of one blanket
   default for everything.

### What would you change if you wanted a second cache with *different*
serialization or TTL?

You'd add a call to `.withCacheConfiguration("otherCacheName",
someOtherRedisCacheConfiguration)` on the same `builder` inside this
customizer's lambda — `cacheDefaults` and `withCacheConfiguration`
compose; the named override wins for that specific cache, and
everything else still falls back to the default.

### One-line mental model to keep forever

> `cacheDefaults` is a blanket rule, not a targeted one — it silently
> reaches every current *and future* cache in the app unless someone
> deliberately carves out an exception with
> `withCacheConfiguration(...)`. When you only have one cache, that
> distinction is invisible; it stops being invisible the moment a
> second cache is added.

### Where this leads next (don't chase these yet, just note them)

- If an "edit destination" or click-analytics feature ever adds a
  second `@Cacheable` method, revisit whether it should share this
  default or need its own `withCacheConfiguration(...)` entry —
  particularly around TTL, since `shortUrls`'s 1-hour TTL
  (`spring.cache.redis.time-to-live`) might not fit a different kind
  of cached data.
- How `RedisCacheConfiguration.defaultCacheConfig()` itself picks
  *its* starting defaults (TTL, key prefixing) before your
  `.serializeValuesWith(...)` call narrows just the serializer part of
  it — worth reading once, briefly, to know what you're *not*
  overriding.

---
