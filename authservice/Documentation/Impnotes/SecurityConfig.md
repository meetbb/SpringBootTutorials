# SecurityConfig — Deep Dive Notes

Our file, for reference:

```java
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtAuthFilter jwtAuthFilter) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/auth/register", "/auth/login", "/auth/refresh", "/auth/logout").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
```

---

## Q1: What problem does this class actually solve?

**It's the one place that writes down, in code, every security *rule* for this app — which URLs need proof of identity and which don't, whether sessions exist, whether CSRF protection applies — so Spring Security has an actual policy to enforce instead of just defaults.**

`JwtAuthFilter` (see `JwtAuthFilter.md`) only ever *decides* "is this request authenticated as this email" and leaves a note in the `SecurityContextHolder`. It never blocks anything. Something has to be the part that actually says "`/auth/register` is fine without that note, but `/auth/me` is not" — and *that* is `SecurityConfig`'s whole job. Without it, Spring Security's auto-configuration defaults would apply instead (every endpoint protected, a generated login form, in-memory sessions) — none of which fit a stateless JWT API.

---

## Q2: Why is this a `@Bean` method returning `SecurityFilterChain`, instead of a class that `extends WebSecurityConfigurerAdapter` (which is what most older tutorials still show)?

### The big picture

**Spring Security moved from "override methods on a base class" to "build and return one `SecurityFilterChain` object" — the newer style is just normal Spring dependency injection applied to security config instead of a special inheritance hook.**

### Why does this even work? (the problem it solves)

`WebSecurityConfigurerAdapter` (pre–Spring Security 5.7) forced exactly one security configuration per app, defined by overriding `configure(HttpSecurity http)` on a subclass — classic inheritance-based customization. That's inflexible: what if an app genuinely needs *two* different filter chains for two different sets of endpoints (e.g. one for a public API, one for an admin UI)? The adapter pattern has no clean way to express that, and it's also now deprecated.

The replacement: declare a `SecurityFilterChain` as a plain `@Bean`. Spring Security's auto-configuration looks for any bean of that type in the application context and uses it as *the* security configuration — no subclassing, no overriding. If an app needed multiple chains, it would just declare multiple `SecurityFilterChain` beans, each scoped to different request paths via `.securityMatcher(...)` — composition instead of inheritance.

### What actually happens, step by step

1. At startup, Spring Boot's auto-configuration checks: "did the app define its own `SecurityFilterChain` bean?" It did (this method) — so Spring Boot's own default chain (the one that would protect everything and generate a login form) is **not** created at all.
2. To build `filterChain`, Spring needs an `HttpSecurity` object and a `JwtAuthFilter` — both are supplied automatically via **method parameter injection**, the same dependency injection idea as constructor injection on a `@Service`, just expressed as parameters on a `@Bean` method instead. `HttpSecurity` is a builder object Spring Security itself provides; `JwtAuthFilter` is our own `@Component` from `JwtAuthFilter.md`.
3. The method configures that `HttpSecurity` builder (CSRF, sessions, authorization rules, filter ordering — Q3 through Q6) and calls `.build()`, which produces the actual `SecurityFilterChain` object — an ordered list of servlet filters plus the rules for which ones apply to which requests.
4. That returned object is registered as a Spring bean. From then on, *every* incoming HTTP request to this app is routed through this exact filter chain before it ever reaches a `@Controller`.

### One-line mental model to keep forever

> `SecurityFilterChain` isn't configuration *metadata* that Spring reads — it IS the literal list of filters that will run on every request. Building and returning it from a `@Bean` method is how we hand Spring Security a chain we assembled, instead of asking it to assemble one from inherited defaults.

---

## Q3: Why `.csrf(csrf -> csrf.disable())` — isn't disabling a security protection dangerous?

### The big picture

**CSRF protection defends against a browser silently riding on a user's existing *session cookie* to make an unwanted request. This app has no session cookies at all — so there's nothing for CSRF protection to defend, and leaving it on would only break legitimate requests.**

### Why does this even work? (the problem it solves, and why it doesn't apply here)

CSRF (Cross-Site Request Forgery) is specifically a cookie-based-session attack: a user is logged into `bank.com` (their browser holds a session cookie for it), then visits `evil.com`, which secretly fires a request to `bank.com/transfer`. The browser automatically attaches `bank.com`'s cookie to that request — because browsers attach cookies to *any* request to the matching domain, regardless of which site initiated it — so the request looks authenticated to `bank.com`, even though the real user never meant to send it. CSRF tokens exist to close that gap: a hidden token value that `evil.com` has no way to know or forge, required on every state-changing request.

This project's authentication doesn't involve cookies at all. The client holds the JWT itself (typically in memory or local storage) and must deliberately attach it as an `Authorization: Bearer <token>` header on every request — that header is never sent automatically by the browser the way a cookie is. A malicious site has no way to "ride along" with a value it doesn't have access to and can't make the victim's browser attach automatically. Since the entire attack depends on automatic credential attachment, and this app never does that, CSRF protection has nothing left to protect — it would only add a required token Postman/API clients would have to carry for no actual security benefit.

### One-line mental model to keep forever

> CSRF protection matters when the browser auto-attaches credentials (cookies). It's irrelevant the moment the client must manually attach the credential (a Bearer header) on every request — which is exactly this project's design.

---

## Q4: What does `.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))` actually turn off?

### The big picture

**By default, Spring Security creates an `HttpSession` (backed by a `JSESSIONID` cookie) the first time anything touches the security context during a request. `STATELESS` is the explicit instruction: never create one, never read one, ever.**

### Why does this even work? (the problem it solves)

This is the direct, concrete version of the "stateless authentication" idea from `SYSTEM_DESIGN.md` section 1: if the server remembers logged-in users via a session stored in its own memory, that memory only exists on *one* server instance. Scale to multiple servers behind a load balancer, and a session created on server A means nothing to server B.

Spring Security's session handling is "on" by default because most traditional (form-login, cookie-based) apps need it. For a JWT-based API, a session would be redundant at best — the JWT itself already carries everything needed to identify the user on every single request, so there's nothing a session would add except a second, conflicting source of "who is this."

### What actually happens, step by step

1. Without this setting, the first time `SecurityContextHolder.getContext().setAuthentication(...)` runs inside `JwtAuthFilter`, Spring Security's session-handling machinery would, by default, persist that `SecurityContext` into an `HttpSession` so a *future* request carrying the same session cookie wouldn't need to re-authenticate.
2. `STATELESS` turns that persistence off entirely. Spring Security will never call `request.getSession()` to create one, and will never attempt to read an existing one, no matter what.
3. Practical consequence: there is no `JSESSIONID` cookie, and no server-side session memory of any kind. Every single request — even two in a row from the same client — is re-authenticated from scratch, purely by `JwtAuthFilter` re-validating whatever `Authorization` header that particular request carries.
4. This is *why* this project can have zero shared session storage and still correctly identify users across multiple hypothetical server instances — exactly the "any instance can verify any token" point in `SYSTEM_DESIGN.md` section 6.

### One-line mental model to keep forever

> `STATELESS` isn't an optimization — it's the thing that makes "no server-side memory of logged-in users" actually true, instead of merely intended.

---

## Q5: How does `.authorizeHttpRequests(...)` decide which rule applies to a given request — and why does order matter?

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/auth/register", "/auth/login", "/auth/refresh", "/auth/logout").permitAll()
        .anyRequest().authenticated())
```

### The big picture

**This reads top to bottom, like a chain of `if`/`else if` — the first matching rule wins, and `anyRequest()` is deliberately placed last because it's a catch-all that must never get the chance to run before a more specific rule does.**

### What actually happens, step by step

1. Spring Security builds an ordered list of (matcher → required access) rules from this lambda, in the exact order they're written.
2. For an incoming request, it walks the list from the top and uses the **first** matcher that matches the request's path.
3. `.requestMatchers("/auth/register", "/auth/login", "/auth/refresh", "/auth/logout").permitAll()` — if the path is exactly one of these four, the required access is "none" (`permitAll`): the request proceeds regardless of whether `JwtAuthFilter` set any authentication at all. This has to be true for these four specifically — a client calling `/auth/login` by definition doesn't have a token yet.
4. `.anyRequest().authenticated()` — this matches *everything else*, and requires that `SecurityContextHolder` already contain a valid, authenticated `Authentication` object (the note `JwtAuthFilter` leaves — see `JwtAuthFilter.md` Q5) by the time this check runs. If there's no authentication present, the request is rejected.
5. **Order is load-bearing, not cosmetic.** If `.anyRequest().authenticated()` were written *first*, it would match literally every request — including `/auth/login` — before the `permitAll()` rule ever got a chance to apply, and login itself would become impossible to reach without already being logged in. Listing the specific, narrower rule first and the broad catch-all last is what makes "public endpoints, everything else protected" actually work.

### What does a rejection here actually look like?

This is the mechanism behind the "known remaining gap" already noted in `CURRENT_STATE.md`: when `.anyRequest().authenticated()` rejects a request, that rejection happens **inside the filter chain itself**, before any `@Controller` or `@RestControllerAdvice` (`GlobalExceptionHandler`) ever runs. That's why it currently surfaces as a bare `403` with no JSON body, unlike every other error case in this app — fixing it would mean plugging in a custom `AuthenticationEntryPoint`/`AccessDeniedHandler`, which is the hook Spring Security calls specifically for rejections that happen at this layer.

### One-line mental model to keep forever

> `authorizeHttpRequests` rules are evaluated like a waterfall, first match wins — always put specific exceptions before the general rule, never after, or the general rule swallows everything first.

---

## Q6: `.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)` sits in the middle of this same method chain — what is it actually doing here, and why does it matter that it's configured *in the same builder* as the rules above?

### The big picture

**This single `HttpSecurity` object is being built up piece by piece — CSRF, sessions, authorization rules, and filter ordering are all just different settings on the *same* builder, which is why one object (`http.build()`) can produce one complete, self-consistent filter chain at the end.**

### Why does this even work? (the problem it solves)

`HttpSecurity` follows the builder pattern: each method (`.csrf(...)`, `.sessionManagement(...)`, `.authorizeHttpRequests(...)`, `.addFilterBefore(...)`) configures one aspect and returns the *same* `http` object, so they can be chained fluently. Nothing is actually "built" until `.build()` is called at the end — up to that point, we're just accumulating configuration onto one in-progress object.

`.addFilterBefore` specifically is how this app's *own* filter (`JwtAuthFilter`, a plain `@Component`, not a Spring Security built-in) gets inserted into Spring Security's otherwise-fixed list of built-in filters, at a precise position: immediately before `UsernamePasswordAuthenticationFilter` (Spring Security's own filter for traditional form-login, present in the default chain even though this project doesn't use form login — see `JwtAuthFilter.md` Q3 for the full walkthrough of why that position matters).

### What actually happens, step by step

1. By the time `.addFilterBefore(...)` is evaluated, the `authorizeHttpRequests` rules from Q5 have already been recorded onto the same `http` builder — but recording the *rule* ("this path needs authentication") is a separate concern from recording *where in the chain authentication gets set* (this line). Both end up in the same final `SecurityFilterChain`, but they answer different questions: Q5 answers "is this request allowed through," this line answers "by the time that question is asked, has anything had the chance to say *who* this request is."
2. `.build()` (the final call) takes everything configured across all these chained calls — CSRF off, sessions stateless, these authorization rules, `jwtAuthFilter` inserted at this specific position — and assembles them into one concrete `SecurityFilterChain` object, which is what gets returned and registered as the bean (back to Q2).
3. Every one of these settings has to agree with the others for the whole thing to make sense: stateless sessions (Q4) are what make per-request re-authentication via `JwtAuthFilter` necessary in the first place; `JwtAuthFilter` running before the authorization check (this line) is what makes the `anyRequest().authenticated()` rule (Q5) have anything to actually check; and CSRF being off (Q3) is only safe *because* authentication rides on a manually-attached header, not an automatic cookie. None of these four pieces is self-sufficient — they're one coherent policy, expressed as one chained builder call.

### One-line mental model to keep forever

> Every call in this chain configures the *same* builder — `SecurityConfig` isn't four independent settings, it's one policy, split across four method calls purely for readability, that only becomes a real, enforceable `SecurityFilterChain` the moment `.build()` runs.

### Where this leads next (don't chase these yet, just note them)

- Checkpoint 11 (role-based authorization) would add to the `authorizeHttpRequests` block — e.g. `.requestMatchers("/admin/**").hasRole("ADMIN")` — inserted *before* the `anyRequest()` catch-all, for the exact ordering reason covered in Q5.
- The bare-403 gap (Q5) would be fixed by adding `.exceptionHandling(ex -> ex.authenticationEntryPoint(...))` onto this same `http` builder — one more call in the same chain, following the same "one builder, one coherent policy" pattern.

---

## One sentence to keep

**`SecurityConfig` doesn't do any authentication itself — it's the single declarative policy object that says which URLs need proof of identity, forbids any server-side session memory, disables a cookie-specific protection this app doesn't need, and tells Spring Security exactly where `JwtAuthFilter`'s decision has to run so the policy actually has something to check.**
