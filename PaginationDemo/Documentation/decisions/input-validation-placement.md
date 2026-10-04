# Decision: Where Should Input Validation Live?

*A short guide on validating in the Service layer vs. validating at the DTO/Controller boundary, written from Checkpoint 4 of the Pagination & Sorting project.*

---

## 1. What We Started With (Validation in the Service Layer)

When we first added validation for the `page` and `size` query params, we wrote plain `if` checks inside `ItemService`, right at the top of `getItems()`:

```java
public PagedResponse<ItemResponse> getItems(int page, int size, String sort) {
    if (page < 0) {
        throw new InvalidPageRequestException("page must be >= 0");
    }
    if (size < 1 || size > 100) {
        throw new InvalidPageRequestException("size must be between 1 and 100");
    }
    // ... rest of the method
}
```

The controller just forwarded the raw `page`/`size`/`sort` params straight into the service, with no checks of its own:

```java
@GetMapping
public ResponseEntity<...> getItems(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String sort) {
    return ResponseEntity.ok(itemService.getItems(page, size, sort));
}
```

This worked. A bad request was still rejected with a `400` and a clear message. But it meant an invalid request had to travel all the way into the business logic layer before anything noticed it was invalid.

---

## 2. The Alternative We Considered: Validate in a DTO

The alternative is to wrap the request params in a small object (a DTO — "Data Transfer Object") and let Spring reject bad values automatically, before the controller method even runs:

```java
public class PageRequestDto {
    @Min(value = 0, message = "page must be >= 0")
    private int page = 0;

    @Min(value = 1, message = "size must be between 1 and 100")
    @Max(value = 100, message = "size must be between 1 and 100")
    private int size = 20;

    private String sort;
}
```

```java
@GetMapping
public ResponseEntity<...> getItems(@Valid @ModelAttribute PageRequestDto request) {
    return ResponseEntity.ok(
        itemService.getItems(request.getPage(), request.getSize(), request.getSort()));
}
```

The `@Valid` annotation tells Spring: "check this object's rules before letting the controller method run." If a rule fails, Spring stops the request right there and returns a `400` — `ItemService` is never even called.

We chose this second approach for the project.

---

## 3. The Trade-off

| | Validate in Service | Validate in DTO |
|---|---|---|
| Where a bad request gets caught | Inside business logic, after the request has already traveled through the controller | At the boundary, before business logic runs at all |
| How much code it takes | A few `if` statements — very little code to add | A new small class, plus annotations — a bit more setup |
| How obvious the rules are | You have to read the method body to find the rules | The rules are visible just by looking at the DTO's fields |
| How well it scales | Gets messy as more fields/rules are added | Stays clean — each new rule is one more annotation |

Neither approach is "wrong." The DTO approach costs a little more code up front. In exchange, it keeps invalid data from ever reaching the parts of the app that are supposed to assume the data is already clean.

---

## 4. Why the DTO Approach Is the Better Practice Here

Think of validation like a security checkpoint at a building entrance. You want to check IDs **at the front door**, not after someone has already walked halfway into the building. If you check IDs deep inside the building instead, you've already let an unverified person wander through hallways they shouldn't have had access to — even if you stop them before they reach the valuable room.

Validating in the DTO applies that same idea to code:

- **The Service layer gets to trust its input.** Once a request reaches `ItemService`, it can assume `page` and `size` are already sane. It doesn't need to re-check — because it's impossible for a request to get there in a bad state.
- **The rule is easy to find.** A new developer reading `PageRequestDto` sees every rule for what a valid request looks like, in one place, without having to read business logic.
- **It avoids wasted work.** An invalid request is stopped immediately, before the app does anything else with it — no partial processing, no accidental setup that then gets thrown away.
- **It matches a general software principle: "fail fast, fail at the edge."** The earlier a problem is caught, the cheaper and safer it is to handle.

This is why, between the two approaches, validating at the DTO/boundary is generally considered the stronger default practice — it keeps "is this request even valid?" separate from "what do we do with a valid request?", which are two different jobs.

---

## 5. When Service-Layer Validation Still Makes Sense

Validating in the DTO isn't the right tool for every rule. Some things genuinely can't be checked until you're inside the Service layer — because the Service layer is the only place with the information needed to check them. Validate there when the rule:

- **Depends on data that only the Service/Repository layer has.** Example: our sort-field whitelist (`id`, `name`, `createdAt`) is a dynamic list, not a fixed numeric range — Bean Validation annotations like `@Min`/`@Max` can't express "must be one of these specific values from a set I maintain in code." That's exactly why we kept the sort check in `ItemService`.
- **Needs a database lookup to check.** Example: "this email must not already be registered" — you can't know that without querying the database, which the DTO layer has no business doing.
- **Is a business rule, not an input-shape rule.** Example: "a user can only have 3 active orders at a time" is about business state, not about whether the request itself was well-formed.

**The simple rule of thumb:** if the check only needs the data already inside the request (is this number too big? is this field blank?), validate it at the DTO/boundary. If the check needs something *outside* the request — a database row, a business rule, a dynamically maintained list — validate it in the Service layer.

---

## 6. Summary

- We moved `page`/`size` validation from `ItemService` into a new `PageRequestDto`, validated automatically via `@Valid`.
- This catches bad requests before they reach business logic, instead of partway through it.
- The trade-off is a small amount of extra code (one new class) for a cleaner boundary and clearer, centralized rules.
- Boundary validation is the better default for simple "is this input well-formed?" checks.
- Service-layer validation is still correct when a rule needs information the DTO can't see on its own — which is why the sort-field whitelist stayed in `ItemService`.
