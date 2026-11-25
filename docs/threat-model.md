# Threat model

Status: the load-test minting and cache section (slice 5b) was checked against the implemented code and its tests. Slice 3a matches the implemented code and its tests (API 100% line and branch, compose and cluster e2e). The admin section (slice 3b) was checked against the code (API 128 unit and 272 integration tests, web 294, e2e 85). The checkout section (slice 4a) was checked against the implemented code and its tests (API 154 unit and 456 integration tests, web 391); the payment and webhook section (slice 4b) was checked against the implemented code and its tests (API tests 100% line and branch, web 451, e2e 100 in compose and 99 plus one skipped in the cluster). Design rationale is in ADR 0006 (authentication), ADR 0007 (admin writes), ADR 0008 (checkout) and ADR 0009 (payment).

## Authentication and sessions

### Assets

| Asset | Why it matters |
|---|---|
| Session cookie `__Host-SESSION` | Possession is the signed-in identity |
| Server-side tokens (access, refresh, ID) in Valkey | Refresh token can mint new tokens; the ID token is a logout hint |
| OIDC client secret | Lets an attacker act as the `shop-api` client |
| Admin role | Gates `/api/admin/**` (writes in 3b) |
| Keycloak realm, signing keys and user passwords | Root of trust for identity |
| Valkey and Postgres data | Sessions; catalog and, later, orders |
| Generated secrets in `.cache/secrets/shop.env` | Local credentials for every component |

### Trust boundaries

| Boundary | Crossing | Control |
|---|---|---|
| Browser to gateway | Untrusted input, cookies | Gateway routes only the listed paths; CSP and headers on the web image |
| Gateway to API | Forwarded headers | Gateways set or clear `X-Forwarded-*`; the API builds URLs from `SHOP_PUBLIC_ORIGIN` only |
| Browser to Keycloak | Login, logout redirects | Own origin; only `/realms/shop/` and `/resources/` routed |
| API to Keycloak | Token, JWKS, refresh | Internal URL, timeouts, TLS-less in cluster, NetworkPolicy allows API to 8080 only |
| Keycloak to API | Back-channel logout | Direct to `api:8080`, not routed; logout token validated |
| API to Valkey | Session store | Password, NetworkPolicy (API only), JDK deserialisation allowlist |
| API to Postgres | Catalog data | Existing policies (ADR 0005) |
| Keycloak to its Postgres | Realm data | Separate CNPG cluster, policy limited to Keycloak |

### STRIDE

| # | Threat | Category | Mitigation |
|---|---|---|---|
| A1 | Token theft by XSS | Information disclosure | BFF: tokens never reach the browser; HttpOnly cookie; strict CSP (ADR 0004) |
| A2 | Session fixation | Spoofing | Session id rotated at login (`changeSessionId`); `__Host-` cookie |
| A3 | Login CSRF and authorisation code injection | Spoofing | `state`, `nonce`, PKCE S256 even for a confidential client; exact redirect URI, no wildcards |
| A4 | Open redirect after login or logout | Spoofing | Redirect targets built from `SHOP_PUBLIC_ORIGIN`; SPA `returnTo` parsed with `URL`, same origin, capped length, router navigation only |
| A5 | CSRF on unsafe requests | Tampering | `csrf.spa()` double-submit with `__Host-XSRF-TOKEN` (`SameSite=Strict`) and `X-XSRF-TOKEN`; `SameSite=Lax` session cookie; 403 without token, also for anonymous |
| A6 | Forged or replayed ID, logout tokens | Spoofing | Signature, `iss`, `aud`, expiry checked against the public issuer; logout tokens need `events`, no `nonce` |
| A7 | Back-channel logout hitting the wrong session | Tampering | Registry matches `sid` plus stored `sub` and `iss`; stores no secrets; mappings removed on logout and timeout |
| A8 | Privilege escalation through role claims | Elevation | Allowlist maps `customer` and `admin` only; authorisation by `sub`; roles in ID token only; URL rule plus `@PreAuthorize`; test walks every `/api/admin` handler |
| A9 | Stale privileges after demotion or disablement | Elevation | Revalidation every 5 min via `IdentityRefresher` (refresh-token grant, new ID token); `invalid_grant` fails closed; grace 15 min if Keycloak is down; back-channel logout |
| A10 | Unknown `/api` path reaches an unprotected handler | Elevation | `anyRequest().denyAll()`; unknown paths 401 or 403; management-port `/actuator/env` and `/actuator/info` 401; firewall-rejected paths get 400 problem+json |
| A11 | Host or forwarded-header spoofing | Spoofing | No host-derived URLs; Keycloak ignores forwarded headers; IT with spoofed `X-Forwarded-Host` |
| A12 | Malicious session payload in Valkey (deserialisation) | Elevation | Valkey reachable only from the API, password; JEP 290 allowlist, depth 20; failures delete the session |
| A13 | Secrets in repo, logs or images | Information disclosure | Generated per checkout, mode 600, never printed; per-service variable mapping; `ShopAuthProperties.toString` redacted; Keycloak fails fast on unset placeholders; no test users in the image |
| A14 | Keycloak admin or registration surface exposed | Elevation | `/admin/`, `/realms/master/`, client registration, management port not routed; registration off; `keycloak.spec.ts` asserts 404s |
| A15 | Direct grant, device flow, token exchange abuse | Elevation | Disabled on every client in the realm; the default `admin-cli` client is disabled outright (the admin API is not routed). Asserted in e2e: `shop-api` refuses them, `admin-cli` answers `invalid_client` |
| A16 | Valkey or Keycloak outage | Denial of service | 503 problem+json when a session cookie needs Valkey; public catalog stays up; timeouts of 2 s (Valkey) and 2 s connect, 5 s read (IdP calls) |
| A17 | Session flooding | Denial of service | No anonymous sessions; pre-login session lives 5 min; Valkey `maxmemory 96mb`, `volatile-lru` |
| A18 | Account lockout of a victim | Denial of service | Brute-force protection on (see residual risks) |
| A19 | No record of who acted | Repudiation | Authorisation and logs use `sub`; admin audit log arrives in 3b (see the admin section) |
| A20 | Image supply chain (Keycloak) | Tampering | Pinned by tag and digest; `scan-keycloak` fails on fixable CRITICAL; exceptions expire; HIGH gated in slice 6 |

## Admin product and stock writes (slice 3b)

### Assets

| Asset | Why it matters |
|---|---|
| Catalog integrity (products, prices, active flag) | Prices and visibility drive what customers see and, from slice 4, pay |
| Stock levels (`on_hand`, `reserved`, `version`) | Slice 4 reserves against them; wrong values oversell or block sales |
| Audit log (`admin_audit_event`) | Only record of who changed what; evidence after an incident |
| Admin session | Authorises every write |

### Trust boundaries

| Boundary | Crossing | Control |
|---|---|---|
| Browser to API (admin writes) | JSON bodies, cookies, `traceparent` header | CSRF token, `ADMIN` role, Bean Validation, version check; the trace id is untrusted |
| Admin module to catalog and inventory | In-process calls | Modulith allowed dependencies; method-level `@PreAuthorize` on the module APIs |
| API to Postgres | Writes and the audit insert | One transaction per change; row locks; `lock_timeout` 3 s; audit triggers |
| Stored text to other users' browsers | Names, descriptions, audit values | React escapes; no `dangerouslySetInnerHTML`; plain-text constraint; CSP |

### STRIDE

| # | Threat | Category | Mitigation |
|---|---|---|---|
| W1 | CSRF on admin writes | Tampering | Every method on `/api/admin/**` needs the CSRF header; 403 without it; tested per route in `AuthorizationMatrixIT` and e2e |
| W2 | Customer or anonymous calls an admin route | Elevation | URL rule `hasRole("ADMIN")`, class-level or method-level `@PreAuthorize` on the services and module APIs, `AdminEndpointsAreProtectedIT` walks every public method; anonymous 401, customer 403; a non-OIDC admin principal gets 403 and writes no audit row |
| W3 | Mass assignment (`id`, `version` on create, `reserved`, `slug` on update) | Tampering | Input records expose only the allowed fields; unknown properties ignored; `AdminProductIT` posts extra fields and asserts they have no effect |
| W4 | Input abuse: control or format characters, wrong JSON types, over-long or missing fields | Tampering | `@PlainText` on code points, fixed bounds, boxed `@NotNull` fields, Jackson coercion off, 400 with `errors`; web schemas mirror the rules but the server is authoritative |
| W5 | Stored XSS through names, descriptions or audit values | Tampering, information disclosure | Text rendered by React (escaped), no raw HTML, strict CSP (ADR 0004), bidi controls rejected on input and `<bdi>` in the audit view |
| W6 | Lost update (two admins editing, or an admin editing against the seed) | Tampering | Version in the body, `FOR UPDATE`, 409 `version-conflict`; seed upsert bumps `product.version`; stock seed guarded by `version = 0` |
| W7 | Stock set below reservations | Tampering | `reserved <= on_hand` check constraint plus a 400 against the locked row; seed guard `reserved <= EXCLUDED.on_hand` |
| W8 | Audit tampering | Tampering | Insert-only repository; triggers raise on `UPDATE`, `DELETE`, `TRUNCATE`; no foreign key, so deleting a product cannot cascade into the log; tests never truncate (see R21 for the owner role) |
| W9 | Repudiation: an admin denies a change | Repudiation | Same-transaction audit row with `sub` as actor; `actor_name` is display only; failed calls write nothing, so the log never holds a change that did not happen |
| W10 | Audit and change diverge | Tampering | One transaction; a failing audit insert rolls back the change (`AuditRollbackIT`) |
| W11 | Forged audit trace id | Repudiation | The trace id is client-controlled and labelled `Trace (unverified)`; never evidence (R26) |
| W12 | Enumeration by product id | Information disclosure | Ids are served only by admin routes (`no-store`); the public API uses slugs and hides inactive products |
| W13 | Lock contention or a held lock blocks writers | Denial of service | `lock_timeout` 3 s gives 503 `Retry-After: 3`; migrations use a 5 s lock timeout; one row per change, no deadlock order |
| W14 | Duplicate slug used to take over or probe slugs | Tampering | `ON CONFLICT (slug) DO NOTHING` gives 409; slug immutable after create; no other integrity error is swallowed |
| W15 | Echoed input or database detail in errors and logs | Information disclosure | Messages never echo values; the data-access handler logs the exception class and the first `SQLException`'s SQLState only, never a message |
| W16 | Stale privileges on writes | Elevation | Overdue revalidation gives 503 on admin writes (R14); `invalid_grant` ends the session |
| W17 | Retry or double submit | Tampering | `PUT` carries a version, `POST` is unique by slug, so a retry gets 409; the web client never resubmits on a 5xx |

## Cart and checkout (slice 4a)

Each claim below was checked against the 4a code. Design rationale is in ADR 0008. The payment and webhook parts are in the next section.

### Assets

| Asset | Why it matters |
|---|---|
| Stock (`on_hand`, `reserved`) | Overselling loses money and trust; leaked or stuck `reserved` blocks sales |
| Prices and order snapshots | The customer pays what the order says; a wrong snapshot is a wrong charge |
| Orders and order history | Personal purchase data, scoped to one customer |
| `orders.email` | Personal data at rest (R45) |
| Idempotency keys | Decide whether a retry creates a second order |
| Customer session | Authorises cart and checkout calls |

### Trust boundaries

| Boundary | Crossing | Control |
|---|---|---|
| Browser to API (cart, checkout, orders) | JSON bodies, `Idempotency-Key`, cookies | Session, CSRF, `CUSTOMER` role, Bean Validation, 16 KiB body cap |
| Order module to inventory | In-process reservation calls | Named interface `inventory::reservations`; `admin` cannot reach it; `Propagation.MANDATORY` |
| API to Postgres | Row locks, conditional updates | Global lock order, `lock_timeout` 3 s, check constraints |
| Several API replicas to one database | Expiry job, concurrent checkouts | `FOR UPDATE SKIP LOCKED` batches, row locks, no in-memory state |
| Identity provider claims to stored data | `email`, `email_verified` | Only a verified, bounded address is stored |

### STRIDE

Caps are per account; accounts are free once self-registration arrives (R36 in 4b).

| # | Threat | Category | Mitigation |
|---|---|---|---|
| K1 | IDOR: reading or cancelling another customer's order | Information disclosure, tampering | Queries filter `customer_subject = :sub`, with `FOR UPDATE` where they lock, never lock then compare; a foreign or unknown id is 404; order ids are random UUIDs, scoped, not a secret; `OrderEndpointsIT` covers read and cancel of another customer's order |
| K2 | Anonymous or non-customer reaches cart or checkout | Elevation | URL rule `hasRole("CUSTOMER")`, `@PreAuthorize` on services, anonymous 401, no customer role 403; `AuthorizationMatrixIT` covers every new route |
| K3 | CSRF on cart, checkout or cancel | Tampering | Every unsafe method needs the CSRF header (A5); `CsrfIT` and e2e cover the new routes |
| K4 | Price tampering: client states a price or stale total | Tampering | The client sends no prices; the server re-prices under `FOR SHARE` on the product rows and compares `expectedTotalMinor` (409 `price-changed`); lines snapshot the price at commit (closes R29) |
| K5 | Stale cart: lines changed in another tab or by a bot | Tampering | `cartVersion` must match under the cart row lock, else 409 `cart-changed`; every line change bumps the version |
| K6 | Race oversell: many customers, one last unit | Tampering | Conditional `UPDATE ... WHERE on_hand - reserved >= :q` plus `CHECK (reserved <= on_hand)`; `StockRaceIT` (fifty customers for ten units, an admin lowering stock, checkouts racing the expiry job) asserts no oversell and that `reserved` equals the sum of active reservations |
| K7 | Deadlock or lock pile-up between checkout, expiry and admin writes | Denial of service | One global lock order (ADR 0008), ascending ids, `releaseAll` aggregates per product; `lock_timeout` gives 503 (R43); `StockRaceIT` crossed-order variant (20 runs) asserts no 503 and no `deadlock detected` in the Postgres log |
| K8 | Replayed or double-submitted checkout | Tampering | `Idempotency-Key` unique per customer; only 201 is stored; a replay returns the current order with `Idempotency-Replayed: true`; same key with another body is 422; a duplicate in flight waits on the unique row, then 409 `idempotency-in-progress` |
| K9 | Idempotency key used across customers | Information disclosure | Key is unique per `(customer_subject, key)`; another customer's identical key is independent |
| K10 | Hoarding stock without paying | Denial of service | 15 minute reservations, at most 3 pending orders, 20 lines, quantity 10 per line, per-product cap 10 across pending orders (409 `reservation-limit`); per account only (R36 in 4b, slice 5c rate limits). Both caps count only unpaid orders still inside their hold (`expires_at > now`): an overdue order keeps its stock until the expiry job releases it (every 30 s), so for up to that window a customer can reserve on top of it |
| K11 | Expiry job misses or double-releases | Tampering | `SKIP LOCKED` batches of 50 under the order lock; release only `ACTIVE` reservations; two job instances over 250 orders release each once (`ReservationExpiryIT`); lazy expiry on cancel closes the gap between runs |
| K12 | Input abuse: negative or fractional quantity, huge bodies, bad slugs or key | Tampering | Bounds 1 to 10, Jackson coercion off (400), `Idempotency-Key` typed as UUID, bodies on `/api/**` capped at 16 KiB before parsing (413 `body-too-large` first; a body with a non-JSON or missing `Content-Type` gets 415 `unsupported-media-type` before any read; Tomcat `max-http-form-post-size` is 16KB as defence in depth); on routes that never read a body (cancel, `DELETE` cart line) a body is discarded by Tomcat, not refused; type-mismatch problems carry a fixed message that names only the parameter and `instance` is the route pattern, so messages never echo input (`ProblemDetailEchoIT`) |
| K13 | Inactive or unknown product in a cart or order | Tampering | `PUT` refuses it (404); checkout refuses an inactive product (409 `out-of-stock`); `DELETE` still removes it so a cart can always be emptied |
| K14 | Spoofed email receives mail or claims an address | Spoofing | Only an `email` claim with `email_verified = true`, at most 254 characters and with a basic address shape, is stored (`CurrentUserTest`); mail itself comes in 4b |
| K15 | Error text or logs leak data | Information disclosure | Problem details carry fixed messages and never the sent value; stock numbers are not returned, only `available` (R44); jobs log counts only |
| K16 | Stale privileges after a customer is disabled | Elevation | Revalidation as in A9 (R42) |
| K17 | A failed checkout leaves partial state | Tampering | One transaction; any 409 or failure rolls back reservation, order and key row, so a retry runs again |

## Payment and webhook (slice 4b)

Checked against the implemented code and its tests. Design rationale and deviations from the original design are in ADR 0009.

### Assets

| Asset | Why it matters |
|---|---|
| Webhook secret (`SHOP_PAYMENT_WEBHOOK_SECRET`) | Forges payment events; one value signs and verifies |
| Order and payment state | A forged or replayed event could mark an unpaid order paid, or move stock |
| Fake provider tokens | Capability to complete a payment on the provider page |
| Event registry (`event_publication`, dead letter) | Holds pending side effects; poisoned entries replay actions |
| Mailpit contents | Order emails and verification links for every account |
| `orders.email` and `order_email` | Personal data at rest (R45) |

### Trust boundaries

| Boundary | Crossing | Control |
|---|---|---|
| Internet to webhook | Unauthenticated POST, body up to 64 KiB | Own `@Order(1)` stateless chain; HMAC-SHA256 over `t` and raw body; 300 s window; exact path match |
| Provider (the fake) to API | Signed event over HTTP to the pod itself | Same verifier as a real provider would face; the fake shares the key (R47) |
| Browser to fake provider page | Token in the URL | 32 random bytes; any signed-in customer holding it can complete, as on a real page; masked in API logs |
| API to Mailpit | SMTP on 1025 | Local overlay only; NetworkPolicies allow api and keycloak; timeouts 2 s and 5 s |
| Listener to the outside | Mail, refund and webhook calls from async threads | `NOT_SUPPORTED` listeners, short transactions around the I/O, bounded pool |

### STRIDE

| # | Threat | Category | Mitigation |
|---|---|---|---|
| P1 | Forged webhook | Spoofing, tampering | HMAC-SHA256 over `t + "." + rawBody` with the secret, compared with `MessageDigest.isEqual` on decoded bytes; missing, malformed, duplicated or unknown elements give the same 401 `webhook-signature`; the body is parsed only after the check; `WebhookIT` and `WebhookVerifierTest` cover each branch |
| P2 | Replay of a captured event | Tampering | Timestamp window of 300 s (301 s old or ahead gives 401); the event id is stored, a duplicate gets 200 with no effect; payment states are monotonic, so even a fresh id for the same payment changes nothing |
| P3 | Timing attack on the signature | Information disclosure | Constant-time comparison; one fixed error for every failure; the log names the reason, never the header |
| P4 | Oversized or wrong-type body | Denial of service | Counting stream stops at 64 KiB + 1 whatever `Content-Length` says (413, `Connection: close`); `Content-Type` must parse as `application/json` (415); the 16 KiB filter exempts only `POST` with the exact URI (`BodyLimitIT`). Spring's path matching decodes percent-encoded spellings (`/api/payments/%77ebhook`) while the body-limit filter compares the raw URI, so those spellings reach the webhook with its own stricter limits and the same HMAC check; accepted (`WebhookIT`) |
| P5 | Webhook reachable with the wrong chain, or CSRF exemption spreading | Elevation | `@Order(1)` chain matched by `POST /api/payments/webhook` only; no session, no `Set-Cookie`, a session cookie is ignored; every other path, method and variant falls to the main chain's `denyAll`; `CsrfIT` still requires CSRF on every other unsafe route; the single CSRF exemption is `WebhookSecurityConfiguration` (`.csrf(csrf -> csrf.disable())`), and when the repository goes public CodeQL's `java/spring-disabled-csrf-protection` alert on it is dismissed with ADR 0009's text |
| P6 | Valkey outage or a session cookie breaks the webhook | Denial of service | Session filters sit outside both chains and four code paths read the session id even on a stateless chain; all four are switched off: the webhook chain disables `sessionManagement` and anonymous authentication, `spring.mvc.publish-request-handled-events=false` and a no-op `FlashMapManager` (`NoFlashMapManagerConfiguration`) cover the MVC reads. `WebhookValkeyIT` pays an order with Valkey stopped and a session cookie sent along (C18). The last two also remove a Valkey read from every request that carries a session cookie |
| P7 | Secret leak or weak secret | Information disclosure | At least 32 characters, checked at startup with a message that holds no value; `toString` redacts it; never in logs or event payloads; `PaymentPropertiesTest` starts with a 20-character secret and asserts the value appears in neither the failure nor the output (C8). Rotation needs a deploy (R39) |
| P8 | Fake token leaks through access logs | Information disclosure | `ApiRequestFilter` replaces the segment after `/api/fake-psp/payments/` with `***`, with or without a suffix or trailing slash, matching on the routed path (path parameters dropped, percent-decoding, repeated slashes and dot segments collapsed; an undecodable path logs `[undecodable path]`), and `ApiRequestFilterTest` asserts the token is absent from captured output for the page, `/complete`, trailing-slash, encoded, `;`-parameterised and normalised forms (C4). Other sinks still hold it (R48) |
| P9 | Capture for an unknown payment, or two captures for one order | Tampering | Unknown payment: 404 so the provider retries, plus a `payment_webhook_unmatched` row. A second capture (another provider payment id for the same merchant reference, or a success on an order that is already paid) leaves the order unchanged and is refunded, never a no-op (C1) |
| P10 | Amount or currency mismatch | Tampering | Order unchanged, payment refunded for the amount received, anomaly logged; a pending order keeps its reservation and expires normally |
| P11 | Late success takes stock that others reserved, or oversells | Tampering | `takeLate` locks stock rows, checks every line, and takes all or nothing; inside the 30 minute window with every product active, else refund; stock effect is applied before the status is written, with a refund fallback (C2); `LatePaymentIT` checks every `stock` row, its `version` and the invariant `reserved = sum(active reservations)` |
| P12 | Webhook races the expiry job, a refund or a second payment start | Tampering | One lock order (ADR 0008, 0009): order, payment, products, stock; the webhook locks the order, then the payment (`payment.order_id` never changes, so no ownership re-check is needed after the order lock); `PaymentRaceIT` races the webhook against the expiry job, a payment start and a refund, and duplicate deliveries at once |
| P13 | Poisoned or stuck outbox entry | Tampering, denial of service | Events hold ids only, no personal data; listeners run on a bounded pool; entries over 48 attempts move to the dead-letter table and are logged; a backlog inside its backoff cannot hide a due entry (C10); stale in-flight entries recover after `staleness.published` (10 minutes) plus the first backoff step (C11); `EventRetryJobIT` covers the 200-per-run cap, the backlog case and the recovery |
| P14 | Mail failure leaks an address into logs | Information disclosure | Listener failures are rethrown with a message of order id and exception class and no cause; the async handler logs no stack trace; a test with a `MailSendException` naming an address asserts it is absent from the output (C7, `OutboxIT`) |
| P15 | Spoofed or injected recipient | Spoofing, tampering | Mail only to `orders.email`, from a verified, bounded claim refreshed at payment start; the mailer rejects CR, LF, malformed or over-long addresses; self-registration needs email verification; changing the address afterwards in the account console is R51 |
| P16 | Duplicate email | Tampering | `order_email` claim (`SENDING`, `SENT`, `FAILED`); a fresh claim defers instead of sending; a crash between send and commit can send twice (R41) |
| P17 | A hung mail or webhook call exhausts threads or connections | Denial of service | Timeouts (2 s, 5 s), `NOT_SUPPORTED` listeners with no transaction held across I/O, pool size 8 and queue 500, readiness independent of Mailpit; `OutboxIT` shows `GET /api/products` and checkout still answer in 2 s with SMTP hung, and the mail arrives once after it recovers |
| P18 | Another customer's payment page or order | Information disclosure | Payment start is owner-scoped (404 for others, as K1); the fake page is guarded by an unguessable token and the `CUSTOMER` role |
| P19 | Fake provider in a real deployment | Spoofing | `shop.payment.provider` has no default and only `fake` is allowed; WARN at startup; `/api/fake-psp/**` is 404 when off; a cloud overlay must set a real provider (R47) |
| P20 | Webhook tables grow without bound | Denial of service | `PaymentMaintenanceJob` (in `payment`, because `order` may not depend on it) purges `payment_webhook_event` and `payment_webhook_unmatched` after 7 days in batches of 1000, hourly; older replays fail the timestamp check anyway (C15, C21; `PaymentMaintenanceIT`) |
| P21 | Open redirect through `redirectUrl` or `returnUrl` | Tampering | The web accepts only same-origin `/fake-psp/<token>` and `/orders/<uuid>`; others show the generic error |

## Load-test session minting and catalog cache channel (slice 5b)

Checked against the implemented code and its tests (`LoadtestSessionsIT`, `LoadtestAbsentIT`, `LoadtestGuardTest`, `LoadtestRateLimitIT`, `MintLimiterTest`, `CatalogCacheIT`, `CatalogCacheOutageIT`, `CatalogCacheValkeyDownAtStartIT`, `routing.spec.ts`, and the `make k8s-loadtest-guard` lint). Design rationale is in ADR 0011 (minting) and ADR 0012 (cache).

### Assets

| Asset | Why it matters |
|---|---|
| `SHOP_LOADTEST_TOKEN` | The only credential for `POST /api/loadtest/sessions`; whoever holds it can create customer sessions without Keycloak |
| Minted sessions in Valkey | Customer identities with a fake verified email; they share Valkey's 96 MB with real sessions |
| Valkey channel `shop:catalog-changed` | Anyone who can publish to Valkey can make every pod drop its catalog caches |
| Catalog caches (Caffeine, 30 s) | Serve public product data; a stale entry shows an old price or a hidden product for up to 30 s |

### Trust boundaries

| Boundary | Crossing | Control |
|---|---|---|
| Browser or internet to the front door | A request for `/api/loadtest/**`, possibly carrying the token | Base HTTPRoute answers 404 for `PathPrefix /api/loadtest` (longest prefix wins) and the `/api` rule removes `Shop-Loadtest-Token`; `cluster-e2e` and the `make loadtest` probe assert the 404; the profile is off in every stack but a load run |
| `kind` Docker network to NodePort 30090 | `POST /api/loadtest/sessions` with the token | Separate Gateway listener whose route matches only that method and exact path; no host port mapping; the token (R60) |
| Minting request to the API | Token header, no body | Own `@Order(2)` chain for exactly that `POST`; constant-time token check; per-pod limiter; customer role only |
| API pod to Valkey pub/sub | Publish and subscribe on `shop:catalog-changed` | Same password and NetworkPolicy as sessions (only `api` reaches Valkey); the message is never parsed, it only triggers a clear |

### STRIDE

| # | Threat | Category | Mitigation |
|---|---|---|---|
| L1 | The minting profile is enabled where it should not be | Elevation of privilege | No profile group and no profile in the Dockerfile; only the `loadtest` kustomize component sets `SPRING_PROFILES_ACTIVE=seed,loadtest`. `make k8s-lint` runs `k8s-loadtest-guard` over every overlay not named `loadtest*` and fails on any case-insensitive `loadtest` in the whole render (other than the two known base Gateway lines), on a second `value: /api/loadtest` line, on a `$(...)` expansion other than `$(POD_NAME)`, on `spring.application.json` spellings, on `\u` escapes, on `${` in a `value:` line, on `binaryData`, on every `envFrom` and on any `type: RegularExpression` path match (a regex rule such as `/api/load.est/sessions` routed to the API), so `args`, `envFrom` and split-word spellings are caught as well as `env:`. The `${` check covers `value:` lines only, because the real renders carry legitimate `${...}` elsewhere (Keycloak), so `${` in container `args` or ConfigMap data is not caught. The not-found awk also requires the `shop` HTTPRoute to be in namespace `shop` and unique. This catches accidental enabling and the listed spellings; it does not stop a repo writer who hides the profile on purpose, and the layers behind it are the token, `LoadtestGuard`'s origin check and Envoy's 404 at the front door; `make compose-lint` fails if a rendered compose config contains `loadtest` (any case), a `\u` escape or a `spring.application.json` spelling, or if `docker compose config` fails. `LoadtestAbsentIT` proves that with the profile off there is no controller bean and the request is refused by `denyAll` (not by CSRF) with no `__Host-SESSION` |
| L2 | The profile runs behind a real hostname, for example in a cloud overlay | Elevation of privilege | `LoadtestGuard` fails startup unless `shop.auth.public-origin` is on `localhost` or `127.0.0.1` (`LoadtestGuardTest`) |
| L3 | Guessing, leaking or timing the token | Spoofing, information disclosure | At least 32 characters, checked at startup by a check that never echoes the value (blank, short and null tokens fail without it in the message; `toString` prints `<redacted>`); both sides hashed with SHA-256 and compared with `MessageDigest.isEqual`; a wrong or missing token gives one 401 Problem Details and one WARN line with no header value (`aMissingOrWrongTokenIsRefusedWithoutEchoingIt`); the secret is passed to k6 as an environment variable by name, never on a command line; stored as Secret `shop-loadtest` with a hash annotation that rolls the API on change. It is not rotated per run: it lives in `.cache/secrets/shop.env` (R16) |
| L4 | Reaching the endpoint through the front door, or through a path variant that Envoy normalises differently from Spring | Spoofing, elevation of privilege | Envoy 404 on `PathPrefix /api/loadtest`; the `/api` rule strips `Shop-Loadtest-Token`, so a variant that slips through reaches the API with no token; the `loadtest` listener route matches `POST` and exact path only. Enforced in CI: `make cluster-e2e` runs a `routing.spec.ts` test that requires Envoy's own empty 404 (not the API's problem+json) for the exact path, trailing slash, `%6C`, `//`, `/./` and `;jsessionid` forms, so dropping or reordering the `not-found` rule fails the PR. That Gateway 404 test is the CI proof; `make k8s-lint` is a fast static backstop (`require-notfound-rule.awk` requires, inside the `shop` HTTPRoute, a `PathPrefix /api/loadtest` rule with no method and the `not-found` `ExtensionRef` filter). Enforced in kind: `make loadtest` first runs `probe-front-door.js` with the valid token against port 30080 and requires exactly 404 for ten Envoy-normalised spellings and all five methods, and fails on any 2xx or `__Host-SESSION` for the rest (case variants, `%2F`, mid-path `;`, GET, HEAD). In `make e2e` (compose, nginx) the spec only asserts "not 201 and no session cookie" for nine variants, since there is no mint controller there; Spring's chain matches only the exact `POST` (`onlyAnExactPostMints`) and the container and Spring firewall reject encoded slashes (ADR 0003) |
| L5 | A minted session used to reach admin or support routes | Elevation of privilege | The role list is fixed to `customer` in code; no input selects a role; `/api/admin/**` refuses a minted session (`aMintedSessionCannotReachAdminRoutes`). Synthetic subjects `loadtest-<uuid>` cannot collide with Keycloak's plain UUIDs |
| L6 | A minted session outliving its purpose, or fixing a session id | Elevation of privilege, spoofing | No authorized client is stored, so the first revalidation (5 minutes) ends the session (fail closed, as for a revoked login); `maxInactiveInterval` is 5 minutes; the controller invalidates any session sent with the request and always issues a new id (`mintingWithAnExistingSessionIssuesANewId...`); the endpoint exists only while an `overlays/loadtest*` deploy is applied, and `make loadtest` returns to `overlays/obs` from an exit trap that survives a dead output pipe and a hung-up terminal (restore output goes to a file; a failed restore writes `RESTORE-FAILED` markers) |
| L7 | Flooding the mint endpoint to fill Valkey or burn CPU | Denial of service | Per-pod fixed-window limiter, 10 a second by default, 429 with `Retry-After: 1` (`LoadtestRateLimitIT`, `MintLimiterTest`): five pods (HPA maximum of 4 plus one rollout surge pod) allow at most 15 000 sessions in 5 minutes. One session is 2 952 bytes (measured; `LoadtestSessionsIT` asserts under 4 000), about 44 MB raw for 15 000, inside the 96 MB; Valkey runs `volatile-lru`, and sessions carry a TTL, so under memory pressure it evicts sessions (users sign in again) and refuses no writes (R2 is the existing session-flood risk) |
| L8 | Cross-site request forging against the mint route, and CSRF on minted sessions | Tampering | CSRF is off for this one route only: no cookie decides the outcome, the credential is a custom header a cross-site page cannot set, and a forged request creates a session in the attacker's own cookie jar at most. CSRF stays on for every request made with the minted session (`csrfIsStillEnforcedOnTheMintedSession`); k6 reads `__Host-XSRF-TOKEN` from `GET /api/me` and sends it as `X-XSRF-TOKEN` like the SPA |
| L9 | A forged message on `shop:catalog-changed` | Denial of service | The payload is never read; any message only clears the three caches. At worst a publisher forces every read through to Postgres, which is the cache-off cost measured in `docs/performance.md`. Publishing needs the Valkey password, which only the API holds (NetworkPolicy, R16) |
| L10 | Invalidation lost, so a pod serves an old catalog entry | Information disclosure (stale data) | Valkey down at publish time: one WARN, the write stays committed, the 30 s TTL bounds staleness on other pods (`CatalogCacheOutageIT`). Valkey down at pod start: the listener subscribes after readiness with backoff, so the pod serves the catalog meanwhile, with the same TTL bound (`CatalogCacheValkeyDownAtStartIT`). A read that began before a commit can re-cache the old row for up to 30 s (ADR 0012, R63). Checkout re-prices from the database, so a stale price is never charged |

Rate limits, gateway body limits and a Postgres `statement_timeout` are not part of this slice (D38): they move to slice 5c, so slice 5b measures the system without them and `docs/performance.md` says so. This stays an open residual: nothing in 5b limits anonymous traffic to the front door, and the cache only reduces its database cost. The mint limiter (L7) is the only limiter in the cluster and does not change R1, R2, R3, R24, R25, R34, R36, R37 or R40.

## Residual risks

| # | Risk | Impact | Planned handling |
|---|---|---|---|
| R1 | No rate limiting until slice 5c | Credential guessing and API abuse limited only by Keycloak's brute-force protection | Slice 5c |
| R2 | Session flooding (C10) | Bounded by short pre-login TTL and Valkey memory cap, not prevented | Slice 5c rate limits |
| R3 | Brute-force lockout sees only the gateway IP (C20) | Anyone can lock out the admin account | Slice 5c |
| R4 | Cookies are not port-scoped (C9) | `__Host-SESSION` and `__Host-XSRF-TOKEN` from `localhost:8088` also go to `localhost:8089`; the XSRF cookie is script-readable there. Origin isolation (DOM, CSP) still holds | Distinct hostnames in the cloud setup |
| R5 | Access logs may record codes and `id_token_hint` (C16) | Authorisation codes and ID tokens in query strings | Mitigated: Envoy and the compose nginx access logs strip query strings on all paths. Residual: nginx's error log may print a URL with a query in a buffering warning (compose e2e only) |
| R6 | ID token in the logout URL | The `id_token_hint` passes through the browser, history and any intermediary logs | Accepted; the token is short-lived, no refresh token. Keycloak 26.8 accepts an expired hint (C23 confirmed), so no fallback exists |
| R7 | `sslRequired: external` | Keycloak accepts plain http from localhost and private addresses | Local only; the cloud guide sets `all`; no TLS or HSTS locally |
| R8 | JDK serialisation of sessions | Gadget-chain risk if an attacker could write to Valkey | Allowlist filter and network isolation (C7); JSON serialisation if needed |
| R9 | No `jti` replay check on logout tokens (C20) | A replayed token can only sign the user out again | Accepted |
| R10 | Playwright traces on failure record typed test passwords (C20) | Test passwords in CI artifacts | Per-run generated secrets limit impact; noted in `tests/README.md` |
| R11 | Keycloak restart re-creates the realm | New signing keys, SSO sessions lost | Accepted for local; app sessions revalidate and end on rejection |
| R12 | Valkey restart signs everyone out | Availability only | Accepted; no persistence by design |
| R13 | Request with a stale session cookie gets 503 during a Valkey outage | Signed-in users see errors; anonymous browsing unaffected | Accepted |
| R14 | Revalidation interval | A demoted admin can write for up to about 5 minutes (`revalidate-after`) while Keycloak is up. With Keycloak down, admin writes get 503 once the last success is older than 5 minutes (E5), and reads continue up to the 15-minute grace | Accepted; back-channel logout is faster when delivered |
| R15 | Keycloak scan gates CRITICAL only (C5) | Fixable HIGH findings in a third-party image ship, reported not blocked | Gate HIGH in slice 6 |
| R16 | Local secrets file readable by the owner and containers that mount it | Local credentials exposure | Mode 600, git-ignored, per-service mapping; no production secrets |
| R17 | Spring accepts back-channel logout tokens with a future `iat` | A token issued by Keycloak for the future still signs the user out; needs a valid signature, `iss`, `aud` | Accepted; Spring's validator, not changed |
| R18 | A logout token without `events` gives a 500 and leaves the session alive | Only a correctly signed token reaches this; the session is not ended by it | Accepted; Keycloak always sends `events` |
| R19 | Keycloak down makes sign-in fail | Sign-in unavailable; existing sessions continue until the 15-minute revalidation grace | Accepted |
| R20 | The client IP from `X-Forwarded-For` is not trustworthy yet | Tomcat trusts private ranges as internal proxies and Envoy appends to the header instead of replacing it, so a client can spoof its address. Nothing uses it today | Slice 5c, before its rate limits rely on it: set `server.tomcat.remoteip.internal-proxies` to the gateway range and make Envoy replace `X-Forwarded-For` |
| R21 | The owner DB role can drop or disable the audit triggers | Then update, delete or truncate the log | Separate audit role in the cloud guide |
| R22 | `actor_name` is user-editable display data | Only `actor_subject` is evidence; a re-created local realm (R11) issues new subjects | Accepted; the API returns both, the audit table shows the name only |
| R23 | A demoted admin can write for up to 5 minutes, and read for up to 15 with Keycloak down | See R14 | Accepted |
| R24 | No rate limit on admin writes | Audit growth is unbounded; the audit page reaches `OFFSET` 1 000 000 (admin-only cost) | Slice 5c |
| R25 | Admin request bodies are bounded only by Jackson's 100M-character string limit | An admin (or a hijacked admin session) can send very large bodies | Slice 5c gateway limits. Anonymous and customer bodies are refused before parsing; from slice 4a customer bodies on `/api/**` are also capped at 16 KiB by an API filter (413, or 415 for a non-JSON body). Only `/api/admin/**` stays bounded by Jackson's limit |
| R26 | The audit trace id is client-controlled | An untrusted correlation hint, never evidence | Accepted; labelled `Trace (unverified)` |
| R27 | Actor names in the append-only log cannot be erased without the owner role | Personal data retention | Retention and erasure procedure (disable trigger, manual and logged) in the cloud guide |
| R28 | A future audit export can carry CSV formula injection | Values starting with `=`, `+`, `-`, `@`, tab or CR | Neutralise those prefixes when an export is built |
| R29 | Price and stock changes race with checkout (closed) | An order could use a stale price or stock | Closed in slice 4a: checkout re-prices under `FOR SHARE` on the product rows (an admin price change waits) and reserves with a conditional stock update; order lines snapshot the price (K4, K6) |
| R30 | On kind and dev the seed re-activates a deactivated seed product and overwrites an admin-created product whose slug a later seed adds; these rewrites of products and stock write no audit rows | Admin edits to seed data are not durable across seed changes and the audit log does not show seed changes | Accepted for local stacks; documented in ADR 0007 |
| R31 | A write reported as 5xx may have committed | The admin may see an error for a change that happened | Safe to retry (version and slug idempotency); the web refetches after a 5xx |
| R32 | Admin writes stay 503 for up to 5 minutes after Keycloak recovers (`Retry-After: 300`) until the next revalidation succeeds | Availability of admin writes only | Accepted |
| R33 | Admin-supplied names can render visually empty or overlap nearby text | Confusing display; no script execution (React escapes, plain-text rule) | Accepted as admin-trusted content |
| R34 | No `statement_timeout`: slow admin searches or deep audit pages hold pool connections shared with the public catalog | Admin-triggered pool pressure | Slice 5c |
| R35 | Module write methods (`ProductAdministration`, `StockManagement`) require an existing transaction, so audit completeness depends on callers going through the admin service | A future caller inside its own transaction could change data without an audit row | Mitigated by `Propagation.MANDATORY` and the method guards, not eliminated; review new callers |
| R36 | Free accounts make every cap per account, not per attacker | Hoarding (K10), cart and order churn, and registration spam are bounded per account only; Mailpit fills with verification mail locally | Slice 5c rate limits; cloud guide (CAPTCHA, real mail provider) |
| R37 | Cancel-and-reorder churn grows `orders`, `order_line`, `reservation`, `payment`, `order_email` and `idempotency_key` without bound | Table growth and slower queries; idempotency keys are purged after 24 h, and `payment_webhook_event` and `payment_webhook_unmatched` after 7 days (`PaymentMaintenanceJob`) | Slice 5c rate limits; retention for orders in the cloud guide |
| R38 | Mailpit is a local-only, unauthenticated mail sink | Anyone who can reach its UI or API reads every mailbox, including verification links, so can verify any address registered locally | Local overlays only, never `base`; NetworkPolicies limit SMTP to api and keycloak; a real mail provider in the cloud |
| R39 | One webhook secret signs and verifies | A leak forges events; rotation needs a deploy; two `v1` values are refused rather than supported | Per-environment secret from `make secrets`; rotation procedure and two-key support deferred to the cloud guide |
| R40 | The public webhook costs a body read (up to 64 KiB) and an HMAC before it can refuse | Cheap abuse of CPU and connections | Size cap, fixed refusals; slice 5c rate limits and gateway limits |
| R41 | Effects are at least once | An email can be sent twice after a crash between send and commit; the refund and webhook calls are idempotent by key and event id | Accepted; documented |
| R42 | Customer revocation lags up to `revalidate-after` (5 min), or the 15-minute grace with Keycloak down | A disabled customer can still use cart and checkout for that long | Accepted, as R14 and R23 |
| R43 | Hot stock rows serialise checkouts; waits past `lock_timeout` (3 s) give 503 | A flash sale on one product shows errors to some customers; no oversell | Retry; slice 5b flash-sale run (`docs/performance.md`): 100 of 100 units sold, no oversell, HPA 2 to 4, checkout p95 about 1.0 s |
| R44 | `available` and `out-of-stock` reveal stock to signed-in customers | A competitor can probe whether a product is in stock. Setting a cart line to 1, 2, ... 10 and reading `available` on each `GET /api/cart` gives the exact free stock whenever it is 10 or less; `out-of-stock` at checkout gives the same answer. Larger stock stays hidden | Accepted |
| R45 | Personal data at rest: `orders.email`, `order_email` and mail kept in Mailpit (local only, until restart or the 500 message cap) | Email addresses kept with orders; no erasure procedure yet | Retention and erasure procedure in the cloud guide |
| R46 | Many concurrent `FOR SHARE` locks on a product (multixacts) delay an admin price change | An admin write waits, or gets 503 past `lock_timeout`, during heavy checkout | Accepted; retry |
| R47 | The fake provider's key equals the verifier's | The HMAC trust boundary is simulated: the fake is in the same process and reads the same property, so the tests prove the verifier, not key separation | Real provider with its own signing secret in the cloud; `fakepsp` ships in the binary and must not be enabled there |
| R48 | Fake payment tokens appear outside the API's masked log: Envoy's access log, the web nginx access log (the `/fake-psp/<token>` page load), browser history, and span URLs once traces are exported (slice 5a) | The token is the capability to complete one test payment | Resolved for spans in slice 5a: `FakePspTokenMasking`, an `ObservationFilter`, canonicalises the raw `http.url` with the same helper as `ApiRequestFilter` (percent-decoding, path parameters dropped, repeated slashes and dot segments collapsed) and rewrites `/api/fake-psp/payments/<token>` (and `/complete`) to `/api/fake-psp/payments/{token}` before export, also for encoded, `;`, `//` and `/./` spellings and on firewall-rejected 400 spans; an undecodable path gets a fixed placeholder. `FakePspTokenMaskingTest` covers those spellings and `TelemetryExportIT` asserts the masked URL is exported and the token is absent from the traces request body (not checked in Tempo's store). Still accepted for the fake (no money, local) in Envoy's and nginx's access logs and browser history; API request logs mask it (P8) |
| R49 | A seed re-run during a rolling deploy can lock rows in another order than ascending id | A deadlock or 503 for one request; local stacks only (the seed does not run in the cloud) | Accepted for local |
| R50 | The expiry job is the only bulk path that returns stock held by overdue orders (cancel releases lazily; reads do not write) | If the job stalls or a batch fails persistently, reserved stock leaks and products read as out of stock until it is fixed; nothing alerts on the job yet | Panels in slice 5a (counter `shop_reservations_released_units_total` and Boot's `tasks.scheduled.execution` timer on the dashboard of ADR 0010); alert rules in slice 5c |
| R51 | Changing the address in Keycloak's account console | Keycloak resets `email_verified` and requires verification at the next sign-in; the session already in use keeps the old verified address, so the confirmation goes there and never to the new one. A flag that stayed true would let order mail reach an unverified address | Covered by `payment.spec.ts` (fresh sign-in shows `Email verification` and gives no shop session; no confirmation reaches the new address); if Keycloak ever stops requiring re-verification the test fails, and the fallback is to remove `manage-account` from the default role (ADR 0009) |
| R52 | Anyone can register a stranger's address, and with password reset off the address stays locked once the verification link expires | The owner of that address cannot register; with a real mail provider the flow sends verification mail to any address | Cloud guide: password reset or cleanup of unverified accounts, CAPTCHA, rate limits (slice 5c) |
| R53 | A dead-lettered refund leaves the order in `REFUND_PENDING` (a dead-lettered webhook delivery leaves it unpaid) | Money held with no automatic follow-up; `EventRetryJob` logs a WARN per listener with the count when it parks publications, but nothing alerts yet | Panel in slice 5a (gauge `shop_events_dead_letter_rows`); alert rules in slice 5c; manual resubmission from the dead-letter table (README) |
| R54 | The realm hard-codes `smtpServer.host: mailpit` | A cloud overlay that forgets to replace it fails loudly (verification mail cannot be sent) and leaks nothing | Cloud overlay replaces the SMTP settings |
| R55 | The `obs` namespace has no NetworkPolicies (slice 5a) | Any pod in the cluster can reach Prometheus, Loki, Tempo, the collector and Grafana, push forged telemetry or read logs and traces | Accepted for a local, single-tenant kind cluster; policies belong with the cloud overlay (slice 6). `shop` allows only `api` to the collector on 4318 |
| R56 | Grafana accepts anonymous Viewer requests at `grafana.localhost:8088` in kind, and `127.0.0.1:3000` in compose | Anyone who can reach `127.0.0.1:8088` (or port 3000) reads dashboards, and through the datasources all metrics, logs and traces; logs and traces show routes, ids and the application's log lines | Loopback only, local only; Viewer cannot edit and has no Explore, so debugging needs the admin login (kind: password from `.cache/secrets/shop.env`; compose: otel-lgtm's built-in admin, local only). Cloud: authenticate Grafana |
| R57 | A client-supplied `traceparent` sets the trace id of its request | A caller can pick trace ids to pollute or collide with traces and log correlation; no authorisation depends on it | Accepted; sampling and storage are bounded by 6 h retention in kind |
| R58 | If the collector or a backend is down, OTLP export fails | The API drops telemetry and logs export errors; requests are not affected (export is asynchronous). Dashboards have gaps. With the `obs` overlay applied and `obs` uninstalled the API keeps logging failures until `make deploy` restores `local` (the local overlay removes the obs-only NetworkPolicy) | Accepted; export is off by default and enabled only by the `obs` overlay or `make dev OBS=1` |
| R59 | Dormant minting code ships in the production image (D35) | One image serves every overlay, so the controller classes exist in production; with the profile off no bean is created and the path is refused by `denyAll` (`LoadtestAbsentIT`) | Accepted; a second image would not be the artifact that was tested. Lint guards, the origin guard and the profile-off test are the controls; a cloud overlay gets its own lint guard in slice 6 |
| R60 | While an `overlays/loadtest*` deploy is applied, anyone on the Docker `kind` network, or on the WSL host through the node container's IP, can reach NodePort 30090 and mint customer sessions if they hold the token (C22) | Customer-only sessions with fake verified addresses; they can fill carts, place orders and send mail to Mailpit, and add up to about 44 MB (5 pods, 15 000 sessions) to Valkey; no admin access (L5) | Accepted for a local single-tenant cluster: no host port mapping, the token is the barrier, the endpoint exists only during a run and the Makefile removes it afterwards. Residual: a SIGKILL, a host or WSL shutdown, or a failed restore leaves minting live until `make deploy OVERLAY=obs` (which also removes the fixed marker; the run-directory marker stays as a record); `make cluster-e2e` now fails while HTTPRoute `loadtest` exists in `shop`, any Service exposes nodePort 30090 or `build/reports/loadtest/RESTORE-FAILED` exists. Orders and carts created stay in the kind database until `make cluster-down` |
| R61 | The mint limiter is per pod and in memory, so the cluster-wide rate is the limit times the pod count and a restarted pod starts a fresh window | Up to 50 mints a second with five pods; bounded by L7's Valkey arithmetic | Accepted; a shared limiter would add a Valkey dependency to a test tool. Rate limits for the shop itself are slice 5c |
| R62 | Anyone with the Valkey password can publish to `shop:catalog-changed` | Forces every pod to drop its caches and read through to Postgres; no data crosses the channel | Accepted; the password and NetworkPolicy already protect Valkey (R8, R16) |
| R63 | Catalog reads can be up to 30 s stale on any pod (TTL, a missed broadcast, or a read that started before the commit and cached the old row after it) | A customer can see an old price, description or a just-deactivated product; checkout re-prices and re-checks `active` under lock (ADR 0008), so no order uses stale data | Accepted; 30 s TTL (ADR 0012); `admin-writes.spec.ts` polls with reload for 5 s to cover broadcast delivery, not the TTL |
| R64 | The `productPage` cache key `(category, sort, page, size)` has an effectively unbounded anonymous key space (any category string, page 0 to 10 000) | A caller can churn the cache and push popular entries out; memory stays bounded by `maximumSize=2000` per cache and Caffeine's admission policy limits the eviction of hot entries; the cost is read-through to Postgres, the cache-off cost | Accepted; free-text search is already excluded for the same reason (ADR 0012). Also recorded there: `@EnableCaching(order = HIGHEST_PRECEDENCE)` runs caching before method security, so a cached method must never carry `@PreAuthorize` |

## Out of scope for this section

TLS and HSTS, MFA, a real payment provider and a production mail provider (cloud or later); supply chain signing (slice 6).
