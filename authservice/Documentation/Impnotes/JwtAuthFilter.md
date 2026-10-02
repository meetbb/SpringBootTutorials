# JwtAuthFilter — Deep Dive Notes

Our file, for reference:

```java
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader(AUTH_HEADER);

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(BEARER_PREFIX.length());

        if (jwtService.isTokenValid(token) && !jwtService.isRefreshToken(token)) {
            String email = jwtService.extractEmail(token);
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }
}
```

---

## Q1: What problem does this class actually solve?

**It's the thing that turns "a string in a header" into "Spring Security considers this request logged in."**

Nothing in Spring Boot automatically knows what a JWT is — `Authorization: Bearer eyJhbGc...` is just a header value, as far as the framework is concerned, unless *something* reads it, checks it, and tells Spring Security "yes, trust this request." That something is `JwtAuthFilter`. It runs once per incoming request, before the request ever reaches a `@Controller` method, and makes one decision: is there a valid, non-refresh access token here? If yes, mark the request authenticated. If no, do nothing and move on — the decision of whether that request is *allowed* to proceed belongs to `SecurityConfig`, not to this filter.

---

## Q2: What problem does extending `OncePerRequestFilter` solve, instead of implementing the raw servlet `Filter` interface?

**The big picture:** `OncePerRequestFilter` guarantees this filter's logic runs exactly one time per request — even on internal forwards/includes, where a plain `Filter` could accidentally run twice.

### Why does this matter here specifically?

A servlet container can internally forward one request to another resource (e.g. the Spring MVC dispatch forwarding to an error page on `/error`). A raw `Filter` would see *both* passes through the filter chain and run its logic twice — which, for most filters, is just wasted work, but for a filter that sets authentication, double-running is at best redundant and at worst a source of subtle bugs if the two passes ever disagreed.

`OncePerRequestFilter` is a Spring-provided base class that tracks (via a request attribute) whether it has already run for this exact request, and skips the second call automatically. All you have to do is implement one method — `doFilterInternal` — and never worry about the double-invocation problem yourself.

### One-line mental model to keep forever

> `OncePerRequestFilter` isn't about *when* the filter runs — it's a guarantee about *how many times*: exactly once, no matter how many internal hops the request takes.

---

## Q3: How does this filter actually get wired into the request's path — what makes it run at all?

```java
.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
```

### The big picture

**Spring Security isn't one check — it's a *chain* of filters, each handling one concern, run in a fixed order for every request. `addFilterBefore` is us inserting our filter at a specific point in that existing chain.**

### What actually happens, step by step

1. Spring Security already ships with its own built-in chain of filters (CSRF handling, session management, the username/password login filter, exception translation, authorization checks, and more) — all wired together before your app ever adds anything.
2. `UsernamePasswordAuthenticationFilter` is the filter Spring Security normally uses for traditional form-login (reading a username/password from a login form submission). This project doesn't use form login at all — login happens through our own `/auth/login` controller endpoint — but that filter is still present in the default chain as a fixed reference point.
3. `.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)` says: "insert `JwtAuthFilter` into the chain, positioned to run *before* that filter." In practice this means our filter runs early — before Spring Security's own authentication machinery and before the authorization check (`anyRequest().authenticated()`) decides whether to let the request through.
4. This ordering is what makes the whole scheme work: our filter must set the authentication *before* the authorization decision is made, otherwise there'd be nothing for the authorization check to find.

### One-line mental model to keep forever

> `addFilterBefore` doesn't create a new chain — it inserts our filter into Spring Security's existing one, at the exact point needed so our authentication decision is made before anything downstream asks "is this request allowed?"

### Where this leads next

- `SecurityConfig`'s `.anyRequest().authenticated()` is the actual gatekeeper — `JwtAuthFilter` only ever *supplies information*, it never itself rejects a request with a 401/403.

---

## Q4: Walk through `doFilterInternal` itself — what is actually happening, line by line, on a real request?

### The big picture

**The method only ever does one of two things: silently pass the request through untouched, or mark it authenticated and then pass it through — it never blocks, redirects, or writes an error response itself.**

### What actually happens, step by step

1. `request.getHeader("Authorization")` reads the raw header value sent by the client — something like `"Bearer eyJhbGciOiJIUzI1NiJ9..."`, or `null` if the client didn't send one at all.
2. **The early-exit check:** `if (authHeader == null || !authHeader.startsWith("Bearer "))`. If there's no header, or it doesn't start with the expected prefix (e.g. someone sent `Basic ...` instead, or just the raw token with no prefix), the filter gives up immediately — it calls `filterChain.doFilter(request, response)` to hand the request to the next filter in line, and `return`s. **No authentication is set.** The request keeps going completely unauthenticated — it's up to whatever comes later in the chain (`SecurityConfig`'s rules) to decide if that's a problem.
3. **Extracting the token:** `authHeader.substring(BEARER_PREFIX.length())` strips off the literal `"Bearer "` (7 characters, including the space), leaving just the raw JWT string.
4. **The validity check:** `jwtService.isTokenValid(token) && !jwtService.isRefreshToken(token)`. Both conditions must hold:
   - `isTokenValid` — confirms the signature matches our secret key and the token hasn't expired (see `JwtService.extractAllClaims`, which throws on any tampering/expiry issue, and `isTokenValid`, which catches that and turns it into a clean `false`).
   - `!isRefreshToken` — confirms this isn't a refresh token being used where an access token belongs. Refresh and access tokens are signed with the *same* secret key and have the *same* shape (see `JwtService`), so a refresh token would otherwise pass `isTokenValid` just fine. The `type` claim baked into the token at creation time (`"access"` vs `"refresh"`) is the only thing that tells them apart. Without this second check, a stolen refresh token — which lives far longer than an access token — could be used directly to hit protected endpoints, defeating the entire point of the short-access/long-refresh split (see `SYSTEM_DESIGN.md`, section 4).
5. **If both checks pass:** `jwtService.extractEmail(token)` pulls the subject claim back out (trusted now, because step 4 already proved the signature is genuine), and a `UsernamePasswordAuthenticationToken` is built and stashed via `SecurityContextHolder.getContext().setAuthentication(...)` — this is the actual "mark this request as logged in" step, covered in full in Q5.
6. **Either way, at the very end:** `filterChain.doFilter(request, response)` always runs — whether authentication was set or not. This filter's job is only to *decide*, never to *block*. Passing control down the chain is what lets the next filter (and eventually Spring Security's authorization check) do something with that decision.

### What happens if the token is invalid or missing, concretely?

The request is simply passed along with **no authentication set in the context**. If that request is headed to a public endpoint (`/auth/register`, `/auth/login`, `/auth/refresh`, `/auth/logout`), that's completely fine — `SecurityConfig` already permits those regardless of authentication. If it's headed anywhere else, `anyRequest().authenticated()` is what notices "nobody authenticated this" and rejects it — but that rejection happens in a *different* part of the chain than this filter, which is exactly why that rejection currently comes back as a bare `403` with no JSON body (see `CURRENT_STATE.md`'s "known remaining gap" — `GlobalExceptionHandler` only catches exceptions thrown inside a controller, and this rejection never reaches a controller at all).

### One-line mental model to keep forever

> `doFilterInternal` is a pure *decision* step: it either plants a flag ("this request is authenticated as `email`") in a place later code can check, or plants nothing — and it always lets the request keep moving either way.

---

## Q5: What does `SecurityContextHolder.getContext().setAuthentication(...)` actually *do*? Where does that "flag" live, and who reads it later?

```java
UsernamePasswordAuthenticationToken authentication =
        new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList());
SecurityContextHolder.getContext().setAuthentication(authentication);
```

### The big picture

**`SecurityContextHolder` is a `ThreadLocal` — a storage slot that is private to the one thread handling this one request. Writing to it here is how information set in a filter becomes visible to completely unrelated code (controllers, `@PreAuthorize` checks) later in the *same* request, without passing it through a method parameter.**

### Why does this even work? (the problem it solves)

A servlet container (Tomcat, embedded in Spring Boot) handles each incoming HTTP request on its own thread, and that thread runs the *entire* request from filter chain through to controller and back. If we need "who is the logged-in user" to be visible both here in `JwtAuthFilter` and later inside, say, a controller method or `@AuthenticationPrincipal` parameter, we need some shared storage that's scoped to *this one request's thread* — not a global variable (which would leak between concurrent requests from different users) and not a method parameter (which would mean threading this value through every single layer by hand). A `ThreadLocal` is exactly that: a variable where each thread sees its own private value, invisible to every other thread.

### What actually happens, step by step

1. `new UsernamePasswordAuthenticationToken(email, null, Collections.emptyList())` builds an `Authentication` object — despite the class name (a holdover from Spring Security's form-login origins), this isn't about a username/password form here. The three arguments are: **principal** (`email` — "who"), **credentials** (`null` — we have no password to carry around at this point; the JWT signature already proved identity), and **authorities** (`Collections.emptyList()` — no roles/permissions attached yet, since this project hasn't implemented role-based authorization — see `SYSTEM_DESIGN.md` Checkpoint 11).
2. `SecurityContextHolder.getContext()` fetches the `SecurityContext` object tied to the current thread (creating an empty one if none exists yet for this request).
3. `.setAuthentication(authentication)` stores our `Authentication` object inside that context.
4. From this point forward, for the *rest of this request's lifetime on this thread*, any code can call `SecurityContextHolder.getContext().getAuthentication()` and get back exactly this object — including Spring Security's own authorization filter (the one enforcing `anyRequest().authenticated()`), which checks "is there a non-null, authenticated `Authentication` in the context?" to decide whether to let the request through.
5. **This is also why there's no cleanup code anywhere in this filter.** Once the request finishes, the servlet container's thread either terminates or gets returned to a pool and reused for an unrelated future request. Spring Security actually clears the `SecurityContextHolder` at the end of every request specifically so a reused thread never accidentally starts a new request already "logged in" as the previous request's user. We don't have to manage that — it's handled for us, outside this class.

### Why `null` credentials specifically?

`credentials` in this three-argument constructor is meant for something like a plaintext password used *during* the authentication attempt — proof not yet verified. By the time we reach this line, verification already happened (`jwtService.isTokenValid`, by checking the cryptographic signature). There's nothing left to "prove" with a credentials value, so `null` correctly communicates "already authenticated, nothing more to check."

### One-line mental model to keep forever

> Setting the `SecurityContextHolder` is how `JwtAuthFilter` leaves a note for the rest of this one request to read — "this request belongs to `email`" — written to a thread-private slot that Spring Security itself clears before the thread is ever reused.

### Where this leads next (don't chase these yet, just note them)

- Checkpoint 11 (role-based authorization) is exactly where the empty `Collections.emptyList()` authorities list would start getting populated from the user's `role` field — that's what `@PreAuthorize("hasRole('ADMIN')")`-style checks actually read.
- The bare-403 gap noted in Q4 — fixing it means adding a custom `AuthenticationEntryPoint`, which is the hook Spring Security calls specifically when `anyRequest().authenticated()` rejects a request that never got this far.

---

## One sentence to keep

**`JwtAuthFilter` doesn't guard anything itself — it runs once per request, early in Spring Security's existing filter chain, and either leaves a thread-local note saying "this request is `email`, proven by a valid signature" or leaves no note at all, trusting `SecurityConfig`'s rules downstream to act on whichever is true.**
