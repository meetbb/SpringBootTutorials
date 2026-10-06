# In-Memory vs Redis-Backed Rate Limiting

## What is a rate limiter actually doing?

Every time a request comes in, a rate limiter has to answer one question:

> "Has this key (a user ID, an IP address, an API key, etc.) already used up its allowed quota in the current time window?"

To answer that question, the limiter needs to remember something **between requests** — for example, a counter, a bucket of "tokens", or a list of timestamps. The counter has to live *somewhere*.

The whole difference between "in-memory" and "Redis-backed" rate limiting comes down to one question:

**Where does that counter/state live, and who else needs to see it?**

---

## Option 1: Single-instance, in-memory

The state (the counters) lives inside a normal Java data structure — something like a `ConcurrentHashMap<String, Bucket>` — sitting in the memory of **one running copy** of your Spring Boot application.

### Why it's simple
- No network calls. The check happens entirely inside the JVM.
- Latency is tiny (nanoseconds to microseconds).
- No extra infrastructure to run or manage.

### The problem it has
Most real applications don't run as a single copy. They run as **multiple instances** behind a load balancer, so that if one instance crashes or gets overloaded, traffic can still be served.

If each instance keeps its own in-memory map, then each instance only knows about the requests *it personally* received. It has no idea what the other instances have seen.

**Example:** Say your rate limit is "10 requests per minute per user". You run 2 instances behind a load balancer. A single user's 20 requests get split roughly 10 to instance A and 10 to instance B. Each instance thinks the user has only made 10 requests — within the limit — and allows them all. The user just made 20 requests in a minute, double what you intended, and nothing caught it.

### Analogy
Imagine a nightclub with 3 separate doors, each with its own bouncer keeping a private tally of how many times they've personally seen a particular guest tonight. If the guest keeps switching doors, every bouncer thinks it's the guest's first visit. Each bouncer is doing their job correctly — but the *club's* rule isn't being enforced correctly.

---

## Option 2: Distributed, Redis-backed

Instead of each instance keeping its own counters, all instances read and write counters stored in **Redis**, a separate, shared, fast in-memory data store.

### Why this fixes the problem above
Now there is only **one** copy of the counter for each key, no matter how many app instances exist. Every instance checks and updates the *same* counter, so the limit is enforced correctly across the whole system.

### The new problem this introduces: a race condition

If you implement the check carelessly as two separate steps —
1. `GET` the current count from Redis
2. If it's under the limit, `INCR` it

— you've created a gap between "check" and "act". Two requests can arrive at nearly the same moment:

- Request A reads the count: 9 (limit is 10). Looks fine, proceeds.
- Request B reads the count: 9 (A hasn't incremented yet). Also looks fine, proceeds.
- Both increment. Now the count is 11 — one more than the limit allowed, and both requests were let through when only one should have been.

This is called a **check-then-act race condition** — the same category of bug that can cause problems like double-spending in a banking system, where two transactions both "see" the same account balance before either one updates it.

### The fix: atomic operations
Redis gives you ways to make "check and increment" happen as a **single, uninterruptible step**, so no other request can sneak in between the check and the update:
- A single `INCR` command combined with `EXPIRE` to reset the window.
- A **Lua script** executed inside Redis with `EVAL`, where the entire check-and-increment logic runs atomically on the Redis server itself.
- Specialized tools built for this exact purpose, like the Redis `CL.THROTTLE` command.

### New costs that come with Redis
- **Latency**: every check now involves a network round trip to Redis (roughly 1–5 milliseconds instead of nanoseconds).
- **A new dependency to manage**: Redis itself needs to be running, monitored, and kept available.
- **A new failure mode**: what happens if Redis is unreachable? Do you let all requests through anyway (**fail-open**, prioritizing availability) or block all requests until Redis is back (**fail-closed**, prioritizing strict enforcement)? This is a real decision every team using Redis-backed rate limiting has to make.

### Analogy
Now imagine all 3 doors share a single bouncer at one podium with a radio connected to every door. Every guest is checked against the same list, so the headcount is always correct. But there's a new risk: what if the radio connection drops? The doors need a plan for what to do in that moment.

---

## Side-by-side comparison

| | Single-instance, in-memory | Distributed, Redis-backed |
|---|---|---|
| Where state lives | Inside one JVM's memory | In a shared Redis store |
| Correct across multiple app instances? | No | Yes |
| Typical latency per check | Nanoseconds to microseconds | ~1–5 milliseconds |
| New infrastructure needed | None | Redis |
| Concurrency risk | Simple — handled by normal in-process locking | Real distributed race condition — needs an atomic operation (e.g. Lua script) to fix |
| New failure mode to plan for | None | What to do when Redis is unreachable (fail-open vs fail-closed) |
| What it teaches | Core rate-limiting algorithms (token bucket, sliding window, etc.) | Distributed systems fundamentals (atomicity, network failures, shared state) |
