# Threat model

Status: slice 3a matches the implemented code and its tests (API 100% line and branch, compose and cluster e2e). The admin section (slice 3b) was checked against the code (API 128 unit and 272 integration tests, web 294, e2e 85). Checkout and webhook sections arrive in slice 4. Design rationale is in ADR 0006 (authentication) and ADR 0007 (admin writes).

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

## Residual risks

| # | Risk | Impact | Planned handling |
|---|---|---|---|
| R1 | No rate limiting until slice 5 | Credential guessing and API abuse limited only by Keycloak's brute-force protection | Slice 5 |
| R2 | Session flooding (C10) | Bounded by short pre-login TTL and Valkey memory cap, not prevented | Slice 5 rate limits |
| R3 | Brute-force lockout sees only the gateway IP (C20) | Anyone can lock out the admin account | Slice 5 |
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
| R20 | The client IP from `X-Forwarded-For` is not trustworthy yet | Tomcat trusts private ranges as internal proxies and Envoy appends to the header instead of replacing it, so a client can spoof its address. Nothing uses it today | Before slice 5 relies on it (rate limits): set `server.tomcat.remoteip.internal-proxies` to the gateway range and make Envoy replace `X-Forwarded-For` |
| R21 | The owner DB role can drop or disable the audit triggers | Then update, delete or truncate the log | Separate audit role in the cloud guide |
| R22 | `actor_name` is user-editable display data | Only `actor_subject` is evidence; a re-created local realm (R11) issues new subjects | Accepted; the API returns both, the audit table shows the name only |
| R23 | A demoted admin can write for up to 5 minutes, and read for up to 15 with Keycloak down | See R14 | Accepted |
| R24 | No rate limit on admin writes | Audit growth is unbounded; the audit page reaches `OFFSET` 1 000 000 (admin-only cost) | Slice 5 |
| R25 | Admin request bodies are bounded only by Jackson's 100M-character string limit | An admin (or a hijacked admin session) can send very large bodies | Slice 5 gateway limits. Anonymous and customer bodies are refused before parsing |
| R26 | The audit trace id is client-controlled | An untrusted correlation hint, never evidence | Accepted; labelled `Trace (unverified)` |
| R27 | Actor names in the append-only log cannot be erased without the owner role | Personal data retention | Retention and erasure procedure (disable trigger, manual and logged) in the cloud guide |
| R28 | A future audit export can carry CSV formula injection | Values starting with `=`, `+`, `-`, `@`, tab or CR | Neutralise those prefixes when an export is built |
| R29 | Price and stock changes race with checkout | An order could use a stale price or stock | Slice 4 re-prices and re-checks stock under the stock row lock |
| R30 | On kind and dev the seed re-activates a deactivated seed product and overwrites an admin-created product whose slug a later seed adds; these rewrites of products and stock write no audit rows | Admin edits to seed data are not durable across seed changes and the audit log does not show seed changes | Accepted for local stacks; documented in ADR 0007 |
| R31 | A write reported as 5xx may have committed | The admin may see an error for a change that happened | Safe to retry (version and slug idempotency); the web refetches after a 5xx |
| R32 | Admin writes stay 503 for up to 5 minutes after Keycloak recovers (`Retry-After: 300`) until the next revalidation succeeds | Availability of admin writes only | Accepted |
| R33 | Admin-supplied names can render visually empty or overlap nearby text | Confusing display; no script execution (React escapes, plain-text rule) | Accepted as admin-trusted content |
| R34 | No `statement_timeout`: slow admin searches or deep audit pages hold pool connections shared with the public catalog | Admin-triggered pool pressure | Slice 5 |
| R35 | Module write methods (`ProductAdministration`, `StockManagement`) require an existing transaction, so audit completeness depends on callers going through the admin service | A future caller inside its own transaction could change data without an audit row | Mitigated by `Propagation.MANDATORY` and the method guards, not eliminated; review new callers |

## Out of scope for this section

Checkout, stock reservation, idempotency and payment webhooks (slice 4); TLS and HSTS, MFA, self-registration (cloud or later); supply chain signing (slice 6).
