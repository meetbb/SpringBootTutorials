# Decision Log: Build Order for the Rate Limiter Project

**Date:** 2026-10-06

## Context
Goal for this project (per the user): level up architectural thinking, domain knowledge, and code-level reasoning to a Senior Engineer standard, with the rate limiter as the vehicle. Usage includes both system-design interview prep and code-level/API design interview prep — both equally.

## Discussion
Compared single-instance in-memory rate limiting against distributed Redis-backed rate limiting (full explanation lives in `Documentation/in-memory-vs-redis-rate-limiting.md`). Key point raised: in-memory is simple but breaks under horizontal scaling (multiple instances don't share counters); Redis fixes that but introduces a real distributed race condition (check-then-act) that needs an atomic fix (e.g. Lua script via `EVAL`), plus a new failure mode (Redis unavailable: fail-open vs fail-closed).

## Recommendation
Build **in-memory first** — get a core algorithm (token bucket or sliding-window-counter) correct and tested in a single instance. Then evolve to **Redis-backed**, deliberately reproducing the check-then-act race condition before fixing it with an atomic Lua script.

**Why this order:** it mirrors how real systems actually evolve (simple solution first, then scale-driven redesign), and produces a concrete "it worked, then broke at scale, here's why, here's the fix" narrative — a stronger interview story than starting distributed from day one.

## Status
Proposed — awaiting user's decision on whether to follow this order or go straight to distributed.
