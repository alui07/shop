# Fix brief: "Add to cart" adds to the line (server-side increment)

Branch `fix-add-to-cart-increments` (from `main` at `e3279d9`). Written 2026-11-25 from the code on `main`. Owner decision 2026-11-25 (STATUS.md, "Next: add-to-cart increments"): option 2, server-side: a new operation that increments a cart line, capped at 10 with a clear answer, idempotent with `Idempotency-Key` like checkout; `PUT /api/cart/lines/{slug}` stays for the cart page.

## 1. What is wrong today

- `apps/web/src/components/AddToCart.tsx` calls `putCartLine(slug, quantity)` (`apps/web/src/api/cart.ts`), which sends `PUT /api/cart/lines/{slug}`. `CartService.putLine` sets the quantity, so a product already in the cart with 2 becomes 3 after "Add 3", not 5. ADR 0008 ("Cart model") and README line 122 document this as intended.
- Nothing else is wrong: the cart page (`CartView`) correctly uses `PUT` to set quantities.

## 2. Facts from the code that shape the design

| Fact | Where |
|---|---|
| Carts are for signed-in customers only (D21). URL rule `hasRole("CUSTOMER")` on `/api/cart/**`, `@PreAuthorize` on `CartService`; CSRF (`csrf.spa()`) on every unsafe method | `auth/SecurityConfiguration.java` lines 88-104, `cart/CartService.java` |
| Every cart write locks the cart row first via `lockOrCreate` (`INSERT ... ON CONFLICT (customer_subject) DO UPDATE ... RETURNING`), then reads lines, writes, bumps `version` | `cart/CartRepository.java`, `cart/CartService.putLine` |
| `PUT` does no stock check; `available` on the returned line shows shortage | `CartService.cartLine` |
| Limits: quantity 1..10 (`CartRules.MAX_QUANTITY`, DB `CHECK (quantity BETWEEN 1 AND 10)`), 20 lines (409 `cart-full`) | `cart/CartRules.java`, `V6__cart.sql` |
| Idempotency: `idempotency_key(customer_subject, key uuid, request_hash, response_status, order_id, created_at, expires_at, PK (customer_subject, key))`; `IdempotencyStore.claim` inserts or renews an expired key (`ON CONFLICT ... DO UPDATE ... WHERE expires_at <= :now RETURNING key`); a concurrent duplicate waits on the uncommitted row; `lock_timeout` (3 s, Hikari connection option) becomes 409 `idempotency-in-progress` with `Retry-After: 1`; a live key with another hash is 422 `idempotency-key-reused`; a failed request rolls back and leaves no key; a replay renders current state with `Idempotency-Replayed: true`; 24 h lifetime (`shop.checkout.idempotency-ttl`); hourly `MaintenanceJob` deletes expired keys | `order/IdempotencyStore.java`, `order/CheckoutService.java` (`claimKey`, `replay`, `requestHash`, `digest`), `order/OrderProblem.java`, `order/MaintenanceJob.java`, `order/CheckoutProperties.java`, `V8__idempotency.sql`, ADR 0008 "Idempotency" |
| `IdempotencyStore` is package-private in `order`. `order` depends on `cart`, so `cart` cannot depend on `order` (Modulith; `ModularityTest` verifies) | `order/package-info.java`, `cart/package-info.java` |
| Lock order (ADR 0008 F6): idempotency row, cart row, orders, payment, products, stock | `CheckoutService` Javadoc |
| API limiter `CART_WRITES`: unsafe methods on `/api/cart/**`, 60/min per account; the OpenAPI 429 customizer adds 429 to every operation a rule `appliesTo` | `auth/RateLimitRule.java` line 29, `auth/RateLimitedResponseCustomizer.java` |
| Envoy has no cart-specific rule (only the 200/s `/api` flood rule); `k8s-rate-limit-guard` compares the rendered BTP/CTP documents, which this fix does not change | `deploy/k8s/base/limits.yaml` |
| Body cap filter covers every `/api/**` route that reads a body (16 KiB) | `web/RequestBodyLimitFilter.java`, ADR 0008 F15 |
| Web `problemSchema` (zod object) strips unknown members, keeps `type`, `detail`, `errors` | `apps/web/src/lib/problem.ts` |
| Web `failureMessage` maps `idempotency-in-progress` to an order-specific text ("Your order is still being processed...") | `apps/web/src/lib/failureMessage.ts` |
| TanStack Query 5.107.1 (`retry: false` default only for queries; mutations default to no retry), openapi-fetch 0.17.0, openapi-typescript 7.13.0, zod 4.6.7, Vitest 5.1.0 | `apps/web/package.json`, `apps/web/src/lib/queryClient.ts` |
| k6 scenarios use `PUT` (`loadtest/lib/shop.js` `putCartLine`) to set quantity 1; no load scenario uses the product page button | `loadtest/scenarios/checkout.js`, `flash-sale.js` |
| e2e flows that click "Add to cart" start from an empty cart (`resetShopper`) or a cleared cart after checkout, so add equals set for them | `tests/e2e/specs/checkout.spec.ts`, `payment.spec.ts`, `rate-limit.spec.ts` |

## 3. Design

### 3.1 Endpoint

`POST /api/cart/lines/{slug}/additions`

- Path `slug`: same constraints as `PUT` (`@Size(max = 100) @Pattern(NO_CONTROL_CHARACTERS)`).
- Header `Idempotency-Key`: required, `@RequestHeader UUID` (generated client types it; missing or non-UUID gives the standard 400 `validation` with the fixed message, as checkout).
- Body: the existing `CartLineInput` `{ "quantity": 1..10 }` (the amount to add).
- Responses (all `Cache-Control: no-store`):

| Status | When | Body |
|---|---|---|
| 200 | Added; the line is created if absent. A repeated key with the same request answers 200 again with the cart's current state and `Idempotency-Replayed: true` | `Cart` (same schema as `PUT`) |
| 400 | Bad quantity, wrong JSON type, missing or non-UUID key, bad slug | `validation` |
| 404 | Unknown or inactive product (no cart created, no key kept) | plain 404 problem as `PUT` |
| 409 | `line-quantity-limit` (new, see 3.2); `cart-full` (new line when 20 lines exist); `idempotency-in-progress` with `Retry-After: 1` | Problem Detail with `errors` |
| 413, 415 | Body over 16 KiB, non-JSON body | as `PUT` |
| 422 | `idempotency-key-reused`: live key with a different request (other slug, other quantity, or another operation such as checkout) | Problem Detail |
| 429 | `cart-writes` limiter (added by the customizer automatically) | `rate-limited` |

Why a sub-resource and not `POST /api/cart/lines/{slug}`: the intent ("an addition to this line") is explicit in the contract and the generated client, and the route is distinguishable in metrics (`uri` tag) and logs from `PUT` on the same path. `/api/cart/**` keeps it under the existing URL rule, CSRF, body cap and `cart-writes` limiter with no config change.

### 3.2 Cap behaviour: reject, all or nothing (decided)

If `quantityInCart + quantity > 10`, nothing changes and the answer is 409:

```json
{ "type": "tag:shop.dev,2026:line-quantity-limit", "title": "Conflict", "status": 409,
  "detail": "The line would hold more than the most allowed",
  "errors": [], "quantityInCart": 9, "maxQuantity": 10 }
```

Reasons over clamp-and-report:

- Matches the existing conventions: every cart and checkout limit is an all-or-nothing 409 with a fixed type (`cart-full`, `reservation-limit`, `too-many-pending-orders`); no endpoint returns partial success.
- Keeps the response the plain `Cart` schema shared with `PUT`; clamping would need a wrapper (`added`, `capped`) or a header the UI could miss, and a silent partial add is the same class of surprise the owner just reported.
- Idempotency stays simple: only success is stored (ADR 0008: "Only 201 results are stored. 409s are recomputed"). A clamped result would have to store the clamped amount (new column, migration) for a faithful replay.
- At 10 already, clamping would be a 200 that does nothing, which is ambiguous.
- UX is covered on the client (3.7): the product page already has the cart in the query cache (the header's `CartLink` loads it), so it shows "N in your cart" and offers only `1..10-N`; the 409 is the fallback for a stale tab or a race, and its `quantityInCart` lets the UI say exactly how many more fit.

The two extension members are the customer's own data and fixed numbers, never request input (K15 holds). `errors` stays `[]` to keep the `Problems.problem` shape.

Order of checks under the lock: line limit first, then `cart-full` (a new line can only hit `cart-full`, an existing line can only hit the quantity limit, so the order only matters for readability). No stock check, as with `PUT`.

### 3.3 Concurrency

`CartService.addToLine` runs in one `@Transactional(isolation = Isolation.READ_COMMITTED)` transaction (explicit, as checkout; must not be `REPEATABLE_READ`, whose snapshot would hide the previous holder's commit and fail with a serialization error instead):

1. Claim the idempotency key (3.4). Takes the key row; a duplicate of the same key waits here.
2. Resolve the product (`pricing.findBySlug(...).filter(active)`, else 404).
3. `repository.lockOrCreate(subject, now)`: the cart row lock serialises every writer of this customer's cart (`PUT`, `DELETE`, additions, checkout's `lockForCheckout` and `clear`). Two first-ever additions both run the upsert; the second waits on the unique index and then takes the row lock.
4. `repository.lines(cart.id())` is a new statement, so under READ COMMITTED it sees every addition committed before the lock was granted. Compute `inCart`, check the cap and `cart-full`.
5. `repository.upsertLine(cart.id(), product.id(), inCart + quantity, now)` (existing method), `bumpVersion`, `idempotency.complete(...)`, return the view at `version + 1`.

No lost update: the read-modify-write happens entirely under the cart row lock, and every cart writer takes that lock first. The cap is enforced under the same lock, and the DB `CHECK (quantity BETWEEN 1 AND 10)` is the last guard (a bug would surface as a 500, never as 11). A single conditional `INSERT ... ON CONFLICT DO UPDATE SET quantity = cart_line.quantity + EXCLUDED.quantity WHERE ... <= 10` was considered: atomic on its own, but the cart lock is still needed for `version` and the 20-line limit, and its "0 rows" branch would be unreachable under the lock (dead branch against the 100% branch gate). Not used.

Lock order is idempotency row, then cart row, the same as checkout, so additions and checkouts cannot deadlock. A checkout holding the cart row for over 3 s makes an addition answer 503 (lock timeout, existing `ApiExceptionHandler` behaviour, same as `PUT`).

### 3.4 Idempotency: one shared mechanism, moved to its own module

`cart` cannot reach `order.IdempotencyStore`, and copying it would fork the mechanism. Move it, unchanged in behaviour, into a new application module `dev.shop.api.idempotency` (`allowedDependencies = {"web"}`); `cart` and `order` add `"idempotency"`.

New public API, `idempotency.IdempotencyKeys` (`@Service`, no `@PreAuthorize`: it is only called from guarded services and the maintenance job runs without a user; methods `Propagation.MANDATORY` so they always join the caller's transaction):

```java
public static final String HEADER = "Idempotency-Key";
public static final String REPLAYED_HEADER = "Idempotency-Replayed";
public record Completed(UUID resourceId) {}   // resourceId is null for cart additions

/** Empty: the key is new (or expired and renewed), go ahead. Present: an earlier request with the same hash completed.
    Throws in-progress (lock_timeout) or key-reused (live key, different hash). */
@Transactional(propagation = Propagation.MANDATORY)
public Optional<Completed> claim(String subject, UUID key, String requestHash);

@Transactional(propagation = Propagation.MANDATORY)
public void complete(String subject, UUID key, int responseStatus, UUID resourceId);

/** SHA-256 hex over the operation and the parts joined by "\n". */
public static String requestHash(String operation, Object... parts);
```

- `claim` contains what `CheckoutService.claimKey` and the hash check in `replay` do today: the lock-timeout catch with `LockFailures.isLockTimeout`, then on a live key `find` and compare the hash. TTL comes from new `IdempotencyProperties` (`shop.idempotency.ttl`, default 24h; `shop.idempotency.maintenance-batch-size`, default 1000), registered by an `IdempotencyConfiguration` (`@EnableConfigurationProperties`), replacing `CheckoutProperties.idempotencyTtl` and `maintenanceBatchSize` (no deploy file or test resource sets the old keys; checked with grep). Time: `clock.instant().truncatedTo(MICROS)` as today.
- `IdempotencyProblem` (package-private, `ErrorResponseException` with its own `HttpHeaders` like `OrderProblem`): `inProgress()` (409, `Retry-After: 1`) and `keyReused()` (422), moved from `OrderProblem`. Types stay `Problems.IDEMPOTENCY_IN_PROGRESS` / `IDEMPOTENCY_KEY_REUSED`.
- `IdempotencyStore` moves as is; `complete(subject, key, status, resourceId)` writes `response_status` and the `order_id` column. The column keeps its name: a rename would break old pods during a rolling deploy for no functional gain. A comment on the store says `order_id` holds the resource id and is null for cart additions.
- `MaintenanceJob` moves as `IdempotencyMaintenanceJob` (`@Scheduled(fixedDelayString = "${shop.idempotency.maintenance-interval:1h}")`), same batching.
- Checkout keeps its hash byte-identical: `requestHash("POST /api/checkout", cartVersion, expectedTotalMinor)` yields the same string as today (`"POST /api/checkout\n" + v + "\n" + t`), so keys issued before the deploy still replay. A unit test pins it to a golden value computed from the current code.
- Cart hash: `requestHash("POST /api/cart/lines/{slug}/additions", slug, quantity)`.

Key namespace: shared, `(customer_subject, key)` as today; no migration. Collisions across operations are impossible to mis-serve because the hash starts with the operation: a checkout key reused for an addition (or the reverse) is 422, and checkout's replay only follows `order_id` after the hash matched. Per customer, as today (K9).

Replay for an addition: `claim` returned `Completed`, so render the current cart (find cart, read lines, `view`) and return 200 with `Idempotency-Replayed: true`. Like checkout, the replay shows current state, not a stored body. Only success is stored (`response_status` 200); 404, 409 and validation failures roll back and leave no key, so a retry is evaluated again.

`CheckoutService` becomes: `Optional<Completed> done = idempotency.claim(...)`; if present, `replay(subject, done.get().resourceId())`; at the end `idempotency.complete(subject, key, 201, orderId)`. `CheckoutController` uses `IdempotencyKeys.HEADER` / `REPLAYED_HEADER`. Behaviour and lock order unchanged.

### 3.5 Auth, CSRF, rate limits, body cap

No configuration change, each verified by tests:

- `/api/cart/**` URL rule and `@PreAuthorize("hasRole('CUSTOMER')")` on `CartService.addToLine` (class-level annotation already covers it).
- CSRF: unsafe method, so the SPA CSRF header is required.
- `cart-writes` (60/min per account) matches `POST` on `/api/cart/**`; replays use a token each, as ADR 0013 already says for checkout. Envoy: covered only by the `/api` flood rule, like `PUT`; no BTP change, so `k8s-rate-limit-guard` and ADR 0013 need no edit.
- Body cap: the new route reads a body, so the 16 KiB filter applies.

### 3.6 OpenAPI and generated client

Annotate the new controller method like `putCartLine` plus checkout's `@Header(name = REPLAYED_HEADER)`; 409 description lists `line-quantity-limit` (with `quantityInCart`, `maxQuantity`), `cart-full`, `idempotency-in-progress (with Retry-After)`; 422 `idempotency-key-reused`; 404, 413, 415 as `PUT`. Then `make generate` rewrites `services/api/openapi/openapi.json` and `apps/web/src/api/schema.d.ts` (never hand-edited; `make lint`/`make test` fail when stale).

### 3.7 Web

- `apps/web/src/api/cart.ts`: add `addToCartLine(slug, quantity, idempotencyKey)` calling `apiClient.POST('/api/cart/lines/{slug}/additions', { params: { path: { slug }, header: { 'Idempotency-Key': idempotencyKey } }, body: { quantity } })` through `sendRequest`. Keep `putCartLine` (cart page).
- `apps/web/src/lib/problem.ts`: add optional `quantityInCart` and `maxQuantity` (`z.number().int().optional()`) to `problemSchema`.
- `apps/web/src/components/AddToCart.tsx`:
  - `useQuery(cartQuery)`; `inCart` = this slug's line quantity or 0 (also 0 while loading or on error; the server is authoritative). `maxAddable = 10 - inCart`.
  - Show `In your cart: N` when N > 0. Offer quantities `1..maxAddable`; the chosen value is clamped to `maxAddable` when the cart grows.
  - When `maxAddable` is 0: no select and no button; text `You have 10 of this product in your cart, the most allowed.`
  - Key per click: `onClick={() => add.mutate({ key: crypto.randomUUID(), quantity })}`. The mutation's `retry: (failureCount, error) => failureCount < 2 && retryable(error)` where retryable is status 0, status 5xx, or type `idempotency-in-progress`; `retryDelay: 1000` (as `placeOrder`). Retries reuse the variables, so the same key: a lost response is replayed, never added twice. The button stays disabled while pending (retries included).
  - `onSuccess`: `setQueryData(cartQuery.queryKey, cart)` as now. `onError` for `line-quantity-limit` or `cart-full`: `invalidateQueries({ queryKey: cartQuery.queryKey })` so the count and options refresh.
  - Messages: `line-quantity-limit` with `quantityInCart` below 10: `You have N of this product in your cart. You can add up to M more.`; with 10 (or the field missing): the "most allowed" text above. `idempotency-in-progress` after retries: `Adding to your cart is taking longer than usual. Check your cart before trying again.` (handled before `failureMessage`, whose in-progress text is about orders). 404, `cart-full`, 429 and the fallback stay as today. Success text stays `Added to cart` (e2e relies on it).
- File stays well under 500 lines; extract `addToCartMessage.ts` in `components/` or `lib/` if it helps readability.

### 3.8 What does not change

`PUT` and `DELETE` semantics; migrations (none added); Envoy and k8s manifests; k6 scripts (they set quantities with `PUT`, which stays; no scenario exercises the product page button); `CartView`; `failureMessage`.

## 4. Tests (100% gates: API line and branch; web lines, branches, functions, statements)

### 4.1 API

New `services/api/src/test/java/dev/shop/api/cart/CartAdditionIT.java` (extends `ShopIT`; add a `addToLine(client, slug, quantity, key)` helper there or in `ShopIT`):

- Adds to an absent line (creates the cart at version 1) and to an existing line (sum), version bumps once per addition; `Cache-Control: no-store`.
- Exactly 10 succeeds; 10 + 1 and 9 + 2 give 409 `line-quantity-limit` with `quantityInCart` and `maxQuantity` 10, version unchanged, no `idempotency_key` row for the subject.
- 21st line by addition is `cart-full`; at 20 lines an existing line can still be added to.
- Quantity 0, 11, `"x"`, `1.5`, missing body: 400. Missing key, non-UUID key: 400. Slug with control characters or over 100 characters: 400.
- Unknown and inactive product: 404, no cart created, no key kept.
- No stock check: adding beyond free stock succeeds with `available: false`.
- Replay: same key and body twice gives 200 both times, the second with `Idempotency-Replayed: true`, quantity added once, version bumped once; a `PUT` between them changes what the replay shows (current state).
- Same key with another quantity, another slug: 422 `idempotency-key-reused`, cart unchanged. A key used for checkout then for an addition, and the reverse: 422.
- Same key from another customer: independent. Expired key (move `MutableClock` past 24 h): accepted as new.
- In-progress: hold an uncommitted key row on another connection (as `IdempotencyIT.aRequestWaitingOnARunningOne...`): 409 `idempotency-in-progress`, `Retry-After: 1`, cart unchanged.
- Concurrency (each variant repeated 5 times, `CompletableFuture` on a fixed pool started behind a latch): 16 threads for one customer with distinct keys each adding 1 to an empty line end at exactly 10, with 10 answers 200 and 6 answers 409 `line-quantity-limit`, final version 10, no 503; 8 threads adding 3 end at 9 (3 succeed); 2 threads with the same key end with one addition (one fresh 200, the other a replayed 200 or `idempotency-in-progress`), quantity added once.

Changes to existing API tests:

| File | Change |
|---|---|
| `cart/CartMethodSecurityIT.java` | `addToLine` refused without the customer role |
| `auth/AuthorizationMatrixIT.java` | safe: `GET /api/cart/lines/desk-lamp/additions` 401, 405, 405; unsafe: `POST .../additions` 401, 400, 400; `customerRoutes` adds it among the binding-400 routes (comment "first five" becomes six) |
| `auth/CsrfIT.java` | `POST /api/cart/lines/desk-lamp/additions` in `customerUnsafeRoutes` |
| `web/BodyLimitIT.java` | the new route in `customerRoutesThatReadABody`, `customerUnsafeRoutes`, `formRoutes` (each case needs the key header) |
| `web/ProblemDetailEchoIT.java` | non-UUID `Idempotency-Key` on the new route is not echoed |
| `auth/RateLimitRuleTest.java` | `POST /api/cart/lines/x/additions` is `CART_WRITES`; `appliesTo("POST", "/api/cart/lines/{slug}/additions")` |
| `OpenApiContractIT.java` | 429 list contains `POST /api/cart/lines/{slug}/additions`; committed document regenerated |
| `admin/MandatoryTransactionIT.java` | `IdempotencyKeys.claim` and `complete` refuse to run outside a transaction |
| `order/IdempotencyIT.java` | autowire `IdempotencyKeys`/store from the new package as needed; maintenance-job test moves out |
| `order/CheckoutServiceTest.java` | its three tests (lock timeout, other failure, unknown digest) move to `idempotency/IdempotencyKeysTest.java`; delete the file if nothing is left |
| `order/ReservationExpiryIT.java`, `order/StockRaceIT.java` | `CheckoutProperties` constructor loses two arguments |
| `ModuleRulesTest.java` | no change expected; `ModularityTest` must pass with the new module |

New `services/api/src/test/java/dev/shop/api/idempotency/`: `IdempotencyKeysTest.java` (unit: lock timeout as Spring exception and as SQLState `55P03` gives in-progress with `Retry-After: 1`; other `DataAccessException` rethrown; unknown digest algorithm; golden checkout hash; parts joined with `\n`), `IdempotencyMaintenanceIT.java` (moved batch-deletion test).

### 4.2 Web

Move the "product page purchase controls" block out of `apps/web/src/cartRoute.test.tsx` (259 lines) into new `apps/web/src/addToCart.test.tsx`, switching stubs to `/api/cart/lines/oak-chair/additions`:

- Sends `POST` with body `{quantity: 3}` and a UUID `Idempotency-Key`; shows `Added to cart`; header shows the returned count.
- Two clicks send two different keys.
- A network failure (responder throws), a 500 and an `idempotency-in-progress` 409 are each retried with the same key and then succeed; after three failures the right message shows.
- 404, `cart-full`, 409 `line-quantity-limit` (N below 10 and N = 10, and with the field missing), 422 and 429 are not retried (exactly one request each) and show their messages; `line-quantity-limit` and `cart-full` refetch `/api/cart`.
- With 7 in the cart: `In your cart: 7`, options 1..3; with 10: the "most allowed" text and no button; the chosen value clamps after a successful add shrinks `maxAddable`; with the cart query failing, options 1..10.
- Anonymous visitors still see `Sign in to buy`.

`apps/web/src/test/fixtures.ts` gains helpers only if needed. `cartRoute.test.tsx` keeps the cart page tests (still `PUT`).

### 4.3 End-to-end

New `tests/e2e/specs/add-to-cart.spec.ts` (project `chromium`; registers its own account with `registerAndVerify`, so no other spec shares its cart or `cart-writes` budget; writes only that cart; product `copper-kettle`, seeded and touched by no other spec):

- UI: add 3, then 4: `Cart (7)`, `In your cart: 7`, the select offers 1..3; add 3: `Cart (10)`, the "most allowed" text, no button.
- API (with `apiFetch`, CSRF token): on a fresh line, the same key twice gives one addition and `idempotency-replayed: true`; another body with that key gives 422; at 10, an addition gives 409 `line-quantity-limit` with `quantityInCart` 10; without the CSRF header, 403.
- Close the context in `finally` (as `rate-limit.spec.ts`).

Existing specs: no change needed (their "Add to cart" clicks start from empty carts); the e2e agent confirms by running `checkout`, `payment` and `rate-limit` specs. `rate-limit.spec.ts`'s click now hits the new route, still under `cart-writes`.

## 5. Docs

| File | Change |
|---|---|
| `docs/adr/0008-checkout-and-stock-reservation.md` | Edit in place: "Cart model" (Add to cart now adds through `POST .../additions`, cap 409 `line-quantity-limit`, `PUT` stays for the cart page); "Idempotency" (shared `idempotency` module used by checkout and cart additions, hash starts with the operation, only success stored: 201 checkout, 200 addition; `shop.idempotency.ttl`; `order_id` column holds the resource id); "Endpoint rules" (new type); `MaintenanceJob` name; one dated line under "Deviations". No new ADR: this extends 0008's existing decisions to a second operation |
| `README.md` | API table row for `POST /api/cart/lines/{slug}/additions`; line 122 sentence ("Add to cart adds to the line, up to 10..."); line 124 settings (`shop.idempotency.ttl`, not `shop.checkout.idempotency-ttl`) and problem list (`line-quantity-limit`) |
| `tests/README.md` | API row: `CartAdditionIT`, `IdempotencyKeysTest`, `IdempotencyMaintenanceIT`, six binding routes; web row: add-to-cart tests and new total; e2e row and count: `add-to-cart`; projects paragraph: `add-to-cart.spec.ts` runs in `chromium` with its own account |
| `docs/threat-model.md` | Assets "Idempotency keys" (also decide whether a retried addition adds twice); K3, K12, K13 (new route); K8 and K9 (cart additions share the per-customer key space; the hash includes the operation, so a key cannot be replayed across operations); R37 (keys also from additions, bounded by 60 cart writes a minute per account, purged after 24 h) |
| `docs/adr/0013-...` | none (the `cart-writes` rule already covers `/api/cart/**`) |

## 6. Work packages

Phase 1 runs API and DOCS in parallel. Phase 2 starts WEB and E2E once API has run `make generate` and reported; E2E may write its spec in phase 1 but runs it only at integration. No two packages touch the same file.

### API (Sonnet)

Files: `services/api/src/main/java/dev/shop/api/idempotency/{package-info,IdempotencyKeys,IdempotencyStore,IdempotencyProblem,IdempotencyProperties,IdempotencyConfiguration,IdempotencyMaintenanceJob}.java` (new); delete `order/IdempotencyStore.java`, `order/MaintenanceJob.java`; edit `order/{CheckoutService,CheckoutController,OrderProblem,CheckoutProperties,package-info}.java`, `cart/{CartController,CartService,CartProblem,package-info}.java`, new `cart/CartAddition.java` (`record CartAddition(Cart cart, boolean replayed)`), `web/Problems.java` (`LINE_QUANTITY_LIMIT`); tests in 4.1; `services/api/openapi/openapi.json` and `apps/web/src/api/schema.d.ts` only through `make generate`.

Order: controller signature and annotations first, then `make generate` and report to the orchestrator (unblocks WEB), then the rest.

Acceptance: `make api-lint`, `make api-test` (100% line and branch; run twice, concurrency IT green both times), `make generate` leaves no diff after the final change, `make lint`. Report to `build/reports/fix-add-to-cart-api.md`.

### WEB (Sonnet, after API's `make generate`)

Files: `apps/web/src/api/cart.ts`, `apps/web/src/lib/problem.ts`, `apps/web/src/components/AddToCart.tsx` (and an optional extracted message helper next to it), `apps/web/src/cartRoute.test.tsx`, new `apps/web/src/addToCart.test.tsx`, `apps/web/src/test/fixtures.ts` if needed.

Acceptance: `make web-lint web-test` (100% on all four metrics, no new exclusions). Report to `build/reports/fix-add-to-cart-web.md`.

### E2E (Sonnet)

Files: new `tests/e2e/specs/add-to-cart.spec.ts`; `tests/e2e/specs/support/*` only if a helper is missing.

Acceptance: `make e2e-lint`; at integration `make e2e E2E_ARGS=specs/add-to-cart.spec.ts` then the full suites. Report to `build/reports/fix-add-to-cart-e2e.md`.

### DOCS (Sonnet)

Files: `docs/adr/0008-checkout-and-stock-reservation.md`, `README.md`, `tests/README.md`, `docs/threat-model.md`. Test counts in `tests/README.md` are filled in after integration by the orchestrator.

Acceptance: `make lint`. Report to `build/reports/fix-add-to-cart-docs.md`.

### Integration (orchestrator)

`make generate` (no diff), `make ci`, `make cluster-up deploy cluster-e2e` (app and e2e code change), `make dev` and the owner's browser repro (add 2, add 3, cart shows 5; add up to 10, message). `STATUS.md` updated at each step.

### Prompt rules for every agent

Copy the "Rules for every agent prompt" list from `CLAUDE.md` into each prompt (Makefile only, `sg docker -c` on permission errors, read-only git, 100% coverage with only the two approved exclusions, pnpm release-age gate, pins in `deploy/versions.mk`, no hand-edited generated files, files under 500 lines, reports under `build/`, no secrets). Jackson 3 is `tools.jackson.*` (tests already import `tools.jackson.databind.JsonNode`).

## 7. Risks

- Moving the idempotency code touches checkout, a slice-4 high-risk path. Mitigation: behaviour-preserving move, byte-identical checkout hash with a golden test, `IdempotencyIT` and `CheckoutIT` unchanged in assertions.
- Concurrency IT and the 3 s `lock_timeout`: 16 threads queue on one cart row; each transaction holds it for milliseconds, so no wait approaches 3 s. If Hikari's pool is smaller than 16, threads also wait for a connection, which is harmless. Any 503 in the run is a failure to investigate, not to retry.
- `ReservationExpiryIT` flake and the `CatalogCacheOutageIT` CI hang (STATUS.md) are pre-existing; they are not caused by this fix and should not be fixed in this branch.
- A replay of an addition that completed but whose response was lost shows the current cart; if the user changed the cart in another tab in between, the replay shows that. Same trade-off as checkout replays (accepted in ADR 0008).

## 8. Open questions for the owner

1. Review depth. CLAUDE.md requires a second challenger and a security-only reviewer only on slices 3, 4 and 6. This fix refactors checkout's idempotency mechanism (slice 4 territory) and adds an unsafe customer route. Recommendation: one challenger with the security and failure-mode checklist (as D51 did for 5c) and the second, security-only reviewer on the diff. Lighter option: one challenger and one reviewer.

Everything else is decided above (endpoint shape, reject over clamp, module move, property rename to `shop.idempotency.*`, client-side pre-limit, no loadtest or deploy change).

## Amendments
Accepted by the owner 2026-11-27 from the design challenge (`build/reports/fix-add-to-cart-challenge.md`, CA-n becomes An). These override the body.

- **A1 (CA-1) Final-failure message and cart refresh.** After a final failure with status 0, any 5xx, or `idempotency-in-progress`, `AddToCart` invalidates `cartQuery` and shows `We could not confirm the addition. Check your cart before trying again.` (replaces the in-progress-only text in 3.7). `cartQuery` is invalidated on every error, not only `line-quantity-limit` and `cart-full`. The retry policy is new, not "as `placeOrder`" (which retries once, on in-progress only): retrying 0 and 5xx with the same key is safe because a repeat is a replay. Web test: after three network failures `/api/cart` is fetched again and the text above shows.
- **A2 (CA-2) Missing key shape.** A missing or empty `Idempotency-Key` gives 400 with Spring's default problem (`about:blank`; detail names only the header). A non-UUID key gives 400 `validation`. `CartAdditionIT` asserts status 400 for missing and empty, and `validation` only for non-UUID. Docs and OpenAPI text say the same. Changing the missing-header shape globally is out of scope.
- **A3 (CA-3) Expired-key tests** age the row in SQL (`UPDATE idempotency_key SET expires_at = expires_at - interval '2 days' WHERE customer_subject = :s`), as `IdempotencyIT` does; never move `MutableClock` (the 8 h absolute session timeout reads it).
- **A4 (CA-4) One "most allowed" text.** When `maxAddable` is 0, `AddToCart` renders only the static "most allowed" text with `role="status"`, and no `line-quantity-limit` alert. "Added to cart" stays visible after the add that reaches 10 (e2e checks it). Web test: stale cache of 5 plus a 409 with `quantityInCart` 10 shows the sentence exactly once.
- **A5 (CA-5) Risk, accepted.** During a rollout a new web bundle can reach an old API pod; the button then shows the 404 "no longer available" text for up to one rollout. Accepted (local and kind apply both at once); noted in the ADR 0008 deviation line; no shim. The shared `idempotency_key` table is compatible in both directions.
- **A6 (CA-6) More ADR 0008 edits.** DOCS also edits F7 (new `idempotency` module, `allowedDependencies = {"web"}`; `order` and `cart` add it; `IdempotencyKeys` has no `@PreAuthorize`, like `StockReservations`, and uses `MANDATORY`, covered by `MandatoryTransactionIT`) and F15 (five customer unsafe routes, three that read a body).
- **A7 (CA-7) Concurrency IT** uses 12 threads adding 1 (10 succeed, 2 get 409), below the 15-connection pool. The 8 x 3 and same-key variants stay.
- **A8 (CA-8) Replay helper.** The replay renders through a private `currentCart(subject)` helper shared with `CartService.get`, so no new uncoverable lambda.
- **A9 (CA-9) Moved problems unchanged.** `IdempotencyProblem.inProgress()` and `keyReused()` keep the details "A request with this key is still being processed" and "The key was used with a different request", `Retry-After: 1` on the 409, and `errors: []`.
- **A10 (CA-10) Fake timers.** Export `ADD_RETRY_DELAY_MS`; retry tests use `vi.useFakeTimers({ shouldAdvanceTime: true })` with `advanceTimersByTimeAsync`.
- **A11 (CA-11) Focus.** When the add that fills the line succeeds, focus moves to the "most allowed" text (`tabIndex={-1}`).
- **A12 (CA-12) Threat model R37.** Addition keys are bounded by `cart-writes` (60/min per account), about 18 times checkout's hourly bound; while the limiter store is down (fail-open) only Envoy's 200/s per-IP `/api` rule bounds them; purged after 24 h; residual stays accepted.
- **A13 (CA-13) Fact corrections.** Section 2: the lock order is in ADR 0008 F6, not the `CheckoutService` Javadoc. `rate-limit.spec.ts` clicks on a line already at 1 (it now becomes 2; harmless). Section 4.3: `copper-kettle` is read by `product.spec` and `catalog.spec` and written by no other spec.
