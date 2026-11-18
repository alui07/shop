# Threat model

Status: slice 3a, matches the implemented code and its tests (API 100% line and branch, compose and cluster e2e). Checkout and webhook sections arrive in slice 4. Design rationale is in ADR 0006.

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
| A19 | No record of who acted | Repudiation | Authorisation and logs use `sub`; admin audit log arrives in 3b |
| A20 | Image supply chain (Keycloak) | Tampering | Pinned by tag and digest; `scan-keycloak` fails on fixable CRITICAL; exceptions expire; HIGH gated in slice 6 |

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
| R14 | Revalidation interval | A demoted admin keeps access for up to about 5 minutes (15 with Keycloak down) | Accepted; back-channel logout is faster when delivered |
| R15 | Keycloak scan gates CRITICAL only (C5) | Fixable HIGH findings in a third-party image ship, reported not blocked | Gate HIGH in slice 6 |
| R16 | Local secrets file readable by the owner and containers that mount it | Local credentials exposure | Mode 600, git-ignored, per-service mapping; no production secrets |
| R17 | Spring accepts back-channel logout tokens with a future `iat` | A token issued by Keycloak for the future still signs the user out; needs a valid signature, `iss`, `aud` | Accepted; Spring's validator, not changed |
| R18 | A logout token without `events` gives a 500 and leaves the session alive | Only a correctly signed token reaches this; the session is not ended by it | Accepted; Keycloak always sends `events` |
| R19 | Keycloak down makes sign-in fail | Sign-in unavailable; existing sessions continue until the 15-minute revalidation grace | Accepted |
| R20 | The client IP from `X-Forwarded-For` is not trustworthy yet | Tomcat trusts private ranges as internal proxies and Envoy appends to the header instead of replacing it, so a client can spoof its address. Nothing uses it today | Before slice 5 relies on it (rate limits): set `server.tomcat.remoteip.internal-proxies` to the gateway range and make Envoy replace `X-Forwarded-For` |

## Out of scope for this section

Checkout, stock reservation, idempotency and payment webhooks (slice 4); TLS and HSTS, MFA, self-registration (cloud or later); supply chain signing (slice 6).
