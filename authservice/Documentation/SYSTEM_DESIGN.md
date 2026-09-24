# System Design — Scalable JWT Authentication Service

This document is the plan we follow while building `authservice`. It is written the way you'd sketch a design on a whiteboard in a coding interview: simple, high-level, and just detailed enough to start coding. Deep implementation notes belong in per-topic docs later (like `RedisCacheConfig.md` in URLShortener) — not here.

---

## 1. The Problem

We need a way for users to prove "who they are" on every request, without the server having to remember every logged-in user in memory.

Why does that matter?
- If the server keeps sessions in memory, it only works on **one machine**. Scale to 5 servers behind a load balancer, and a user logged into server A is a stranger to server B.
- We want auth to work the same whether we have 1 server or 100.

**JWT (JSON Web Token) solves this by moving "who is this user" out of server memory and into a signed, tamper-proof token the client carries.** The server doesn't store sessions — it just verifies the token's signature on each request. This is called **stateless authentication**.

---

## 2. High-Level Picture

```
                 ┌────────────────────────┐
                 │        Client          │
                 │ (Postman / Browser /    │
                 │  Mobile App)            │
                 └───────────┬────────────┘
                              │ 1. Register / Login (email + password)
                              ▼
                 ┌────────────────────────┐
                 │     AuthService API     │
                 │  Controller → Service   │
                 └───────────┬────────────┘
                              │ 2. Verify credentials, hash/check password
                              ▼
                 ┌────────────────────────┐        ┌───────────────┐
                 │   Postgres (Users DB)   │        │  Redis (opt.) │
                 │  id, email, passwordHash│        │ refresh tokens│
                 │  roles, createdAt       │        │ / blacklist   │
                 └────────────────────────┘        └───────────────┘
                              │ 3. Issue signed JWT (access + refresh)
                              ▼
                 ┌────────────────────────┐
                 │        Client          │  stores tokens
                 └───────────┬────────────┘
                              │ 4. Sends "Authorization: Bearer <accessToken>"
                              │    on every future request
                              ▼
                 ┌────────────────────────┐
                 │  JWT Filter (Security)  │  verifies signature + expiry
                 │  runs BEFORE controller │  — no DB lookup needed!
                 └───────────┬────────────┘
                              ▼
                 ┌────────────────────────┐
                 │  Protected Controller   │
                 └────────────────────────┘
```

**Key idea:** Step 4 is the whole point. Verifying a JWT is just checking a cryptographic signature (fast, in-memory, no DB call). That's what makes this scale horizontally — any server instance with the same signing key can verify any token, from any other instance.

---

## 3. Core Components (Controller → Service → Repository)

| Layer | Responsibility |
|---|---|
| `AuthController` | Exposes `/register`, `/login`, `/refresh`, `/logout`. No business logic. |
| `UserService` | Registration logic, password hashing, credential checks. |
| `JwtService` (util) | Generates and validates JWT tokens (signing, expiry, claims). |
| `UserRepository` | JPA repository — talks to Postgres. |
| `JwtAuthFilter` | A `OncePerRequestFilter` that runs on every request, reads the `Authorization` header, validates the token, and tells Spring Security "this request is authenticated." |
| `SecurityConfig` | Wires the filter into Spring Security's filter chain; declares which endpoints are public (`/register`, `/login`) vs protected. |
| Postgres | Durable storage for user accounts. |
| Redis (later) | Shared store for refresh tokens / logout blacklist across multiple server instances. |

---

## 4. Two Kinds of Tokens (why not just one?)

- **Access Token** — short-lived (e.g., 15 min). Sent on every request. If stolen, damage window is small.
- **Refresh Token** — long-lived (e.g., 7 days). Used only to get a new access token when it expires, without forcing the user to log in again.

This split is the standard trade-off between **security** (short-lived tokens limit damage) and **user experience** (don't force re-login every 15 minutes).

---

## 5. Implementation Checkpoints

Work through these in order. Each has a **Depends on** line — don't start a checkpoint until its dependency is done.

### Checkpoint 1 — Project Setup
Add dependencies: Spring Web, Spring Security, Spring Data JPA, PostgreSQL driver, a JWT library (e.g. `jjwt`), Validation, Lombok. Confirm the app boots.
**Depends on:** nothing (starting point).

### Checkpoint 2 — User Entity & Database Schema
Create the `User` entity (`id`, `email` (unique), `passwordHash`, `roles`, `createdAt`) and `UserRepository`. Connect to Postgres.
**Depends on:** Checkpoint 1 (needs JPA + Postgres dependency).

### Checkpoint 3 — Registration Endpoint
`POST /register`: DTO + validation (`@NotBlank`, `@Email`), hash the password with `BCryptPasswordEncoder`, save the user. Never store or return plaintext passwords.
**Depends on:** Checkpoint 2 (needs `User` entity + repository).

### Checkpoint 4 — JWT Utility (`JwtService`)
A standalone class that can: generate a token with claims (subject = user id/email, issued-at, expiry), sign it with a secret key, and parse/validate a token (signature + expiry check). Build and unit-test this in isolation, before wiring it into any endpoint.
**Depends on:** Checkpoint 1 (needs the JWT library dependency). Not dependent on the DB.

### Checkpoint 5 — Login Endpoint
`POST /login`: look up user by email, compare password with `BCryptPasswordEncoder.matches()`, and if valid, issue an access token (and refresh token) via `JwtService`.
**Depends on:** Checkpoint 3 (users must exist to log in) and Checkpoint 4 (needs `JwtService` to issue tokens).

### Checkpoint 6 — JWT Filter + Security Config
Build `JwtAuthFilter` to intercept every request, extract the `Authorization: Bearer <token>` header, validate it via `JwtService`, and set the authenticated user in Spring Security's context. Configure `SecurityConfig` to permit `/register` and `/login` publicly and require a valid token everywhere else.
**Depends on:** Checkpoint 4 (needs `JwtService` to validate tokens) and Checkpoint 1 (needs Spring Security dependency).

### Checkpoint 7 — Protected Test Endpoint
Add one simple protected endpoint (e.g. `GET /me`, returns the logged-in user's info) to prove the whole chain works end-to-end: register → login → call `/me` with the token → get data back; call without a token → `401`.
**Depends on:** Checkpoint 6 (the filter must be enforcing auth already).

### Checkpoint 8 — Refresh Token Flow
`POST /refresh`: accept a refresh token, validate it, issue a new access token. Decide where refresh tokens live — start with storing them in Postgres (simplest); Redis becomes worth it once we care about fast lookups/expiry across multiple instances.
**Depends on:** Checkpoint 5 (refresh tokens are issued at login).

### Checkpoint 9 — Logout / Token Revocation
`POST /logout`: invalidate the refresh token (delete it, or mark it revoked). Optional: maintain an access-token blacklist (e.g. in Redis, keyed by token id with a TTL matching the token's remaining life) for immediate revocation before natural expiry.
**Depends on:** Checkpoint 8 (needs a place refresh tokens are tracked).

### Checkpoint 10 — Centralized Exception Handling
A `@RestControllerAdvice` that turns validation errors, bad credentials, and expired/invalid tokens into clean, consistent JSON error responses (`400`, `401`, `403`) instead of stack traces.
**Depends on:** Checkpoint 5 (there need to be failure cases to handle — bad login, expired token, etc.).

### Checkpoint 11 — Role-Based Authorization *(optional stretch)*
Add a `roles` check (e.g. `ADMIN` vs `USER`) so some endpoints require a specific role, not just "any authenticated user."
**Depends on:** Checkpoint 6 (roles need to already be embedded in the token/filter's authentication object).

### Checkpoint 12 — Tests
Add unit tests for `JwtService` (token generation/validation, expiry) and integration tests for the register → login → access-protected-resource flow.
**Depends on:** whichever checkpoint the test targets. *(Per project convention, tests are added only when explicitly requested — this checkpoint is a placeholder, not a default action.)*

---

## 6. Why This Scales (the "so what")

- **No sticky sessions needed** — any instance can verify any token (same signing secret), so a plain round-robin load balancer works fine.
- **The only shared state is optional** — refresh tokens and revocation lists (Checkpoints 8–9) are the *one* place multiple instances need to agree, which is why that's where Redis/Postgres comes in. Everything else (Checkpoint 6's filter) is pure in-memory verification.
- **Natural evolution path:** start fully stateless (Checkpoints 1–7) → add shared state only where a real requirement forces it (revocation) → that's the moment to explain, in an interview, *why* pure stateless JWT isn't quite enough on its own (you can't force-expire a token early without some shared record of it).
