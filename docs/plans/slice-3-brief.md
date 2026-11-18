# Slice 3 implementation brief: auth, roles, admin product and stock management

Branch: `slice-3-auth`. Read first: `CLAUDE.md`, `master-plan.md` (slice 3 line; Auth, Routing, Kubernetes and Docs rows), `slice-2-brief.md` amendments (B2: the browser origin is `http://localhost:8088` in both e2e stacks), `docs/adr/0003` to `0005`, `tests/README.md`. All standing rules apply: every tool runs in a container through the Makefile, pins in `deploy/versions.mk` with Renovate annotations, the 7-day release-age gate, 100% coverage gates with no new exclusions, no git commands except read-only `git status` and `git diff`, no secrets in the repo or terminal, files under 500 lines, `sg docker -c "..."` when the shell lacks the docker group. High-risk slice: two challengers and two reviewers (general, security-only).

## Goal

A visitor signs in through Keycloak, the API holds the tokens in a server-side session, and the SPA only ever sees an HttpOnly `__Host-` cookie. Admins manage products and stock in a small admin UI; every change is audited. Done means:

- `make dev` (http://localhost:5173), `make e2e` and `make cluster-up deploy cluster-e2e` (http://localhost:8088) all sign in and out through a real Keycloak.
- Anonymous users browse the catalog exactly as before. `/api/admin/**` returns 401 Problem Details to anonymous users, 403 to customers, 200 to admins; an unsafe request without a valid CSRF token returns 403.
- Admins can list (including inactive), create, edit, deactivate products and set stock; each change writes an audit row in the same transaction.
- Back-channel logout from Keycloak ends the app session; idle (30 min) and absolute (8 h) timeouts hold.
- The NetworkPolicy test covers the new pods (6 allowed, 16 denied checks).
- `make ci` passes with API and web at 100% coverage; ADR 0006 and 0007, a threat model and the manual checklist are written.

## Versions (read 2026-11-17 from quay.io, Maven Central, npm and the Spring and Keycloak docs; all at least 7 days old)

| Thing | Pin | Source and notes |
|---|---|---|
| Keycloak | `quay.io/keycloak/keycloak:26.8.1@sha256:3f3c5f8e03c614774b4bd9173b88391fefa4153bfc010d8012d59ad825377332` (2026-10-31) | quay.io tag API. 26.8.2 (2026-11-12) is inside the gate. Official image, no Bitnami. PLATFORM confirms with `docker buildx imagetools inspect` that the digest is the multi-arch index |
| Spring Security | 7.1.2 | Managed by Boot 4.1.2 (`spring-boot-dependencies-4.1.2.pom`); reference docs read at 7.1.2 |
| Spring Session | 4.1.1 (Data Redis), Lettuce 7.5.2 | Boot-managed. Starter `spring-boot-starter-session-data-redis` (Boot 4.1.2 docs) |
| Testcontainers | 2.0.5 | Boot-managed; Valkey runs as a `GenericContainer` with `@ServiceConnection(name = "redis")` |
| New API dependencies | `spring-boot-starter-security`, `spring-boot-starter-oauth2-client`, `spring-boot-starter-session-data-redis`; test: Boot 4's security test starter (or `spring-security-test`) | All Boot-managed, no versions in the pom. Check the Boot 4.1 starter list for the exact test starter name |
| `@tanstack/react-form` | 1.33.5 (2026-08-11) | npm; peer `react ^17 \|\| ^18 \|\| ^19`. 2.0 is alpha only. No Zod adapter: Form 1.x takes Zod 4 through Standard Schema |
| Valkey, Postgres | existing `VALKEY_IMAGE`, `POSTGRES_IMAGE`; kind uses the existing CNPG operand pin for `keycloak-db` | Nothing new |
| Not used | `com.github.dasniko:testcontainers-keycloak` 4.4.0 (2026-10-01, Keycloak 26.8, Testcontainers 2.0.5) | Considered and rejected (see Tests) |

Spring Security 7, Spring Session 4 and Keycloak 26.8 are newer than most training data. `CsrfConfigurer.spa()` exists since 7.0 (`CookieCsrfTokenRepository.withHttpOnlyFalse()` plus `SpaCsrfTokenRequestHandler`, which loads the token on every request). `BackChannelLogoutConfigurer.logoutHandler(...)` exists since 6.4. Keycloak uses hostname v2 (`--hostname` takes a full URL).

## Owner decisions needed (before implementation)

| # | Decision | Recommendation |
|---|---|---|
| D1 | Keycloak on its own origin or under the app origin (`/auth`) | **Own origin**: `http://localhost:8089` for compose e2e and kind, `http://localhost:8180` for dev. An XSS in either app cannot read the other's pages, the app's session cookie never goes to Keycloak, and the CSP stays `'self'`. Cost: one more NodePort and host port in kind, and one more Chromium host mapping |
| D2 | Keycloak in kind as well as both compose stacks | **Yes, everywhere.** `cluster-e2e` must exercise the real login, and slice 5's k6 work runs against the cluster |
| D3 | Keycloak's database in kind | **A second CNPG cluster `keycloak-db`** (production-like; about 150 MB). An init container re-imports the realm with `--override true` on every pod start, so the repo stays the source of truth. The alternative, the `dev-file` H2 database on an emptyDir, saves memory but is unsupported in production |
| D4 | Dev and test secrets: OIDC client secret, Valkey password, Keycloak DB and bootstrap-admin passwords, test-user passwords | **Generate per checkout** with `make secrets` into `.cache/secrets/shop.env` (mode 600, never printed, never committed). The alternative is committed dev-only literals, which would widen the current Postgres-password exception in `CLAUDE.md` |
| D5 | Trivy gate on the new `shop/keycloak:dev` image | **Scan and publish the SARIF, but do not gate.** It is a third-party image our Dockerfile only re-optimises, like Postgres. Its bundled jars may carry fixable HIGHs that we cannot fix. Revisit in slice 6 |
| D6 | Scope of the admin UI | **List with search and paging (inactive included), create, edit, deactivate or reactivate, set stock, read-only audit list.** Out of scope: category management, images, hard delete (slice 4 orders will reference products) |
| D7 | Self-registration in Keycloak | **Off** until slice 4 needs customer accounts |
| D8 | Deny-by-default changes unknown `/api/**` paths from 404 to 401 for anonymous callers | **Accept.** `routing.spec.ts` changes accordingly; authenticated callers still see 404 for unknown paths |
| D9 | Session serialisation in Valkey | **JDK serialisation (Spring Session default) for now.** Valkey is reachable only from the API, so the cost is that a Spring Security upgrade can invalidate live sessions, handled as "signed out" (see API). JSON via Jackson 3 can wait for a real need |
| D10 | Coverage exclusions | **None proposed.** Every new class is testable; if an implementer believes otherwise, they stop and ask |

The rest of this brief assumes the recommendations.

## Origins and the issuer (the core of the design)

| Stack | App origin (`SHOP_PUBLIC_ORIGIN`) | Keycloak public origin (`KC_HOSTNAME`) | Issuer (`SHOP_AUTH_ISSUER`) | API back-channel (`SHOP_AUTH_INTERNAL_REALM_URL`) |
|---|---|---|---|---|
| `make dev` | `http://localhost:5173` | `http://localhost:8180` (published `127.0.0.1:8180`) | `http://localhost:8180/realms/shop` | `http://keycloak:8080/realms/shop` |
| `make e2e` | `http://localhost:8088` | `http://localhost:8089` (Chromium maps it to `gateway:8089`) | `http://localhost:8089/realms/shop` | `http://keycloak:8080/realms/shop` |
| kind | `http://localhost:8088` | `http://localhost:8089` (NodePort 30089, listener `idp`) | `http://localhost:8089/realms/shop` | `http://keycloak:8080/realms/shop` |

The trap: Keycloak stamps `iss` from its frontend URL (`KC_HOSTNAME`), but the API container cannot reach `localhost:8089`. With Boot's `issuer-uri`, the API would run OIDC discovery against the browser URL at startup and fail, or, pointed at the internal URL, reject every token because `iss` differs. Design:

- **No discovery.** The API builds its `ClientRegistration` in code from `ShopAuthProperties` (`@ConfigurationProperties("shop.auth")`, validated, all required): `issuerUri` = the public issuer, so Spring checks `iss` on ID and logout tokens. `authorizationUri` = public issuer + `/protocol/openid-connect/auth`, because the browser follows it. `tokenUri` and `jwkSetUri` = the internal realm URL + `/protocol/openid-connect/token` and `/certs`, because only the API calls them. No `userInfoUri`: roles come in the ID token, which saves a call. `providerConfigurationMetadata` holds `end_session_endpoint` = public issuer + `/protocol/openid-connect/logout`. `clientSettings.requireProofKey(true)`, so PKCE S256 is sent even though the client is confidential. Scopes `openid profile email`. `userNameAttributeName` `sub`.
- **No host-derived URLs.** `redirectUri` = `${SHOP_PUBLIC_ORIGIN}/login/oauth2/code/keycloak`, the post-logout redirect = `${SHOP_PUBLIC_ORIGIN}/`, and the login success and failure targets are absolute on the same origin. No `{baseUrl}` anywhere. This settles the `X-Forwarded-*` question from slice 2: security decisions no longer depend on the `Host` or forwarded headers. `server.forward-headers-strategy: native` stays, for logs only.
- **Keycloak**: `KC_HOSTNAME` is the full public URL, which makes `hostname-strict` irrelevant. `KC_PROXY_HEADERS` is unset, so forwarded headers are ignored and cannot be spoofed. `KC_HTTP_ENABLED=true`, `KC_CACHE=local` (one replica, no JGroups discovery). `KC_HEALTH_ENABLED` is baked into the image. Keycloak answers token and JWKS calls on `keycloak:8080` regardless of the `Host` header; only the URLs it renders use the hostname.
- **Only these Keycloak paths are routed publicly** (Keycloak's exposed-path guidance): `/realms/shop/` and `/resources/`. `/admin/`, `/realms/master/` and the management port 9000 are not routed in e2e or kind; dev publishes Keycloak directly so the owner can use the admin console.
- `localhost:8088` and `localhost:8089` are the same site (ports do not count), so `SameSite=Lax` holds through the redirects. Both are secure contexts.

## Keycloak (`deploy/keycloak/`)

- `Dockerfile` (`shop/keycloak:dev`): `ARG KEYCLOAK_IMAGE`; build stage with `KC_DB=postgres`, `KC_HEALTH_ENABLED=true`, `RUN /opt/keycloak/bin/kc.sh build`; final stage copies `/opt/keycloak` and `COPY shop-realm.json /opt/keycloak/data/import/`. The pre-built (`--optimized`) image is required: without it Keycloak re-augments at startup and writes into `/opt/keycloak/lib`, which a read-only root filesystem forbids (Trivy KSV014). `make build` builds it with `--provenance=false --sbom=false` (slice 2 B1); `docker-lint` covers the Dockerfile.
- `shop-realm.json` (realm `shop`), with placeholders resolved from the environment at import:
  - `registrationAllowed: false`, `resetPasswordAllowed: false`, `loginWithEmailAllowed: true`, `bruteForceProtected: true`, `sslRequired: external` (accepts plain http from localhost and private addresses; the cloud guide sets `all`).
  - `ssoSessionIdleTimeout: 1800`, `ssoSessionMaxLifespan: 28800` (aligned with the app), `accessTokenLifespan: 300`, `revokeRefreshToken: true`.
  - Realm roles `customer` and `admin`; default roles include `customer`.
  - Client `shop-api`: confidential, `secret: "${SHOP_AUTH_CLIENT_SECRET}"`, `standardFlowEnabled: true`; implicit, direct access grants, service accounts and device flow off. `redirectUris: ["${SHOP_PUBLIC_ORIGIN}/login/oauth2/code/keycloak"]` (exact, no wildcards), `post.logout.redirect.uris: "${SHOP_PUBLIC_ORIGIN}/"`, `pkce.code.challenge.method: S256`, `backchannel.logout.url: http://api:8080/logout/connect/back-channel/keycloak`, `backchannel.logout.session.required: true`, `frontchannelLogout: false`, `webOrigins: []`. A protocol mapper (realm roles, claim `roles`, multivalued) adds roles to the ID token only, not to the access token or userinfo.
- Test users, for local stacks only, in `deploy/k8s/overlays/local/keycloak/shop-users-0.json` (Keycloak's `<realm>-users-<n>.json` naming). The compose stacks mount the same file, so there is one copy, and it lives inside the kustomization root (kustomize refuses files outside it). Users `customer@example.test` (role customer) and `admin@example.test` (roles customer and admin), passwords `${SHOP_TEST_CUSTOMER_PASSWORD}` and `${SHOP_TEST_ADMIN_PASSWORD}`, emails verified, no required actions. The image never contains test users. If a placeholder variable is unset, Keycloak imports the literal `${...}`; the e2e login then fails, which is the intended loud failure.
- Health: `/health/started`, `/health/live` and `/health/ready` on 9000. The image has no curl or wget, so the compose healthcheck uses bash `/dev/tcp` (`exec 3<>/dev/tcp/127.0.0.1/9000`, send `GET /health/ready`, grep `"UP"`).

## API design (`services/api`)

**Modules (Spring Modulith; `ModularityTest` must pass):**

| Module | Contents | Allowed dependencies |
|---|---|---|
| `web` (existing) | `SecurityConfiguration` (filter chain, CSRF, session, Problem Details entry point and access-denied handler), `ShopAuthProperties`, client registration, logout handlers, `AbsoluteSessionTimeoutFilter`, `ValkeyOidcSessionRegistry`, exception handler | `customer` (role mapping) |
| `customer` (new) | `GET /api/me`, `ShopRoles` (claim to authority allowlist), `CurrentUser` (sub and display name for auditing) | none |
| `catalog` (existing) | public reads unchanged; new public `ProductAdministration` API (list with inactive, get by id, create, update with version check, set active) | none |
| `inventory` (new) | `stock` table, public `StockManagement` API (read, set `on_hand` with version check) | none |
| `admin` (new) | `/api/admin/**` controllers, `AdminProductService` (one `@Transactional` for the catalog or inventory change plus its audit row), audit repository and list endpoint | `catalog`, `inventory`, `customer` |

**Security filter chain** (one chain; management port included):
- `authorizeHttpRequests`: `permitAll` for `GET /api/status`, `GET /api/categories`, `GET /api/products/**`, `GET /api/me`, the error dispatch, and the health probes (`EndpointRequest.to(HealthEndpoint.class)`; without this the probes on 8081 start returning 401). `/api/admin/**` `hasRole("ADMIN")`. `anyRequest().denyAll()`. Login, callback and logout are handled by their filters. Method security (`@EnableMethodSecurity`) adds `@PreAuthorize("hasRole('ADMIN')")` on every admin controller and on `AdminProductService` (defence in depth). A test walks `RequestMappingHandlerMapping` and fails if any `/api/admin` handler lacks it.
- `oauth2Login`: `loginPage("/oauth2/authorization/keycloak")`, so Spring's generated `/login` page is never registered. Success handler: rotate the session (Spring's default `changeSessionId`), store attribute `shop.auth.authenticatedAt`, redirect to `${SHOP_PUBLIC_ORIGIN}/`. Failure handler: log at info without token contents, redirect to `${SHOP_PUBLIC_ORIGIN}/?login=failed`. A `GrantedAuthoritiesMapper` maps the `roles` claim through the allowlist `customer` to `ROLE_CUSTOMER` and `admin` to `ROLE_ADMIN`, and drops everything else. The default `OAuth2AuthorizedClient` store in the session keeps the access and refresh tokens server-side; the ID token is used for `id_token_hint`.
- **Session**: Spring Session on Valkey (`RedisSessionRepository`, the non-indexed default; no keyspace events needed). Cookie via Boot properties: `server.servlet.session.cookie.name: __Host-SESSION`, `secure: true` (explicit, because the request is http behind every proxy), `http-only: true`, `same-site: lax`, `path: /`, no domain. Chromium accepts `Secure` cookies from `http://localhost`, which slice 2 B2 relies on. `spring.session.timeout: 30m` is the idle timeout. The absolute timeout is a filter after `SecurityContextHolderFilter`: if `now - authenticatedAt > shop.auth.absolute-session-timeout` (8h, injectable `Clock`), invalidate and continue anonymously. Anonymous public GETs must not create sessions; only `/oauth2/authorization/**` does. A session that fails to deserialise (D9) is deleted and treated as anonymous, not a 500.
- **CSRF** (Spring Security 7 docs, SPA section): `csrf.spa()`, then `csrfTokenRepository(repo)` where `repo` is `CookieCsrfTokenRepository.withHttpOnlyFalse()` with name `__Host-XSRF-TOKEN`, path `/`, and a customiser `secure(true).sameSite("Strict")`. The `__Host-` prefix blocks cookie tossing from sibling subdomains in the cloud, which plain double-submit is otherwise open to. The header stays `X-XSRF-TOKEN`. `spa()` loads the token on every request, so the cookie reappears on the first response after login or logout clears it. The back-channel endpoint is registered before `CsrfFilter` by Spring and needs no exemption.
- **RP-initiated logout**: `POST /logout` (CSRF required). The success handler extends `OidcClientInitiatedLogoutSuccessHandler` with a `RedirectStrategy` that answers `200 application/json {"redirectUrl": "<end_session_endpoint>?id_token_hint=...&post_logout_redirect_uri=...&client_id=shop-api"}` instead of a 302. A `fetch` cannot follow a cross-origin redirect, and a form post would need Keycloak in CSP `form-action`. The session is invalidated and the cookie cleared before the response.
- **Back-channel logout**: `oidcLogout(o -> o.backChannel(b -> b.logoutHandler(sessionDeletingHandler)))`. Spring validates the logout token (signature against the JWKS, `iss`, `aud`, `events`, no `nonce`, `iat`). `ValkeyOidcSessionRegistry implements OidcSessionRegistry` keeps `shop:oidc:sid:{sid}` to session id and `shop:oidc:sub:{sub}` to a set of sids, each with a TTL equal to the absolute timeout, so it works with several replicas (slice 5); the default registry is in-memory. The handler deletes each returned session id with `SessionRepository.deleteById`. Spring's default handler is not used: it re-POSTs `/logout` to itself with a `JSESSIONID`-style cookie, which breaks with `__Host-SESSION`, Base64 session cookies and our proxies. An IT must prove the registry holds the **post-rotation** session id; if Spring's registration strategy runs before `changeSessionId`, record the mapping in the success handler instead.
- **Problem Details** (`application/problem+json`) for 401 (entry point: "Authentication required"), 403 (access denied), 403 CSRF (`detail: "CSRF token missing or invalid"`), 503 when Valkey is unreachable (`RedisConnectionFailureException`; readiness stays `readinessState,db` so the public catalog keeps serving), 409 (version conflict, duplicate slug), 400 validation with an `errors` extension (`[{field, message}]`).
- Remove the hand-written `nosniff` from `ApiRequestFilter`; Spring Security's header writer takes over. Spring's `Cache-Control: no-store` must not override the catalog's `no-cache` (it skips responses that already set the header; the existing ITs prove it).

**Endpoints and contract** (springdoc; `make generate` updates `openapi.json`, `schema.d.ts` and the route tree):

| Method and path | Role | Body and response |
|---|---|---|
| `GET /api/me` | any | `Me {authenticated: boolean, subject: string\|null, name: string\|null, email: string\|null, roles: ("customer"\|"admin")[]}`, `Cache-Control: no-store` |
| `GET /api/admin/products?q&page&size` | admin | `AdminProductPage` of `AdminProduct {id, slug, name, description, price: Money, category: Category, active, version, stock: {onHand, reserved, version}}`; inactive included; sorted by name |
| `GET /api/admin/products/{id}` | admin | `AdminProduct`; 404 Problem |
| `POST /api/admin/products` | admin | `ProductInput {slug, name, description, priceMinor, categorySlug, active}`, 201 with `Location`; stock row created with `onHand` 0 |
| `PUT /api/admin/products/{id}` | admin | `ProductInput` plus `version`; 409 on a stale version |
| `PUT /api/admin/products/{id}/stock` | admin | `{onHand, version}`; 409 on a stale version; 400 if `onHand < reserved` |
| `GET /api/admin/audit?page&size` | admin | `AuditPage` of `AuditEvent {id, occurredAt, actorName, action, entityType, entityId, changes}`; newest first |

Validation (Bean Validation, mirrored in the published patterns): slug `^[a-z0-9]+(-[a-z0-9]+)*$`, at most 100; name 1 to 120 with no control characters; description 1 to 2000 with no control characters except `\n`; `priceMinor` 0 to 10 000 000; `onHand` 0 to 1 000 000; the category must exist (400). Currency is fixed to `EUR` in this slice and not part of the input. `id` is a path `long` with `@Min(1)`. The OpenAPI document declares a cookie security scheme and the CSRF header on unsafe admin operations.

**Migrations:**
- `V3__stock.sql`: `stock(product_id bigint PRIMARY KEY REFERENCES product(id), on_hand integer NOT NULL CHECK (on_hand >= 0), reserved integer NOT NULL DEFAULT 0 CHECK (reserved >= 0), version bigint NOT NULL DEFAULT 0, CHECK (reserved <= on_hand))`, plus a row with `on_hand` 0 for every existing product. This is the master plan's model; slice 4 adds `reservation` and the conditional `UPDATE`.
- `V4__product_admin.sql`: `product.version bigint NOT NULL DEFAULT 0`, `updated_at timestamptz`.
- `V5__admin_audit.sql`: `admin_audit_event(id bigint identity PK, occurred_at timestamptz NOT NULL DEFAULT now(), actor_subject text NOT NULL, actor_name text NOT NULL, action text NOT NULL, entity_type text NOT NULL, entity_id bigint NOT NULL, changes jsonb NOT NULL, trace_id text)`, index on `occurred_at DESC`. Changes are a before and after map of the changed fields only. The application only inserts (no update or delete code paths); a separate DB role is left to the cloud guide.
- `R__seed_catalog.sql`: add stock for seed products with `ON CONFLICT (product_id) DO NOTHING`, so a re-seed never overwrites admin edits to stock.

**Config** (`application.yaml`): `shop.public-origin` and `shop.auth.{issuer, internal-realm-url, client-id, client-secret, absolute-session-timeout}` with no defaults for secrets; `spring.data.redis.{host,port,password}`; the session and cookie properties above. The `dev` profile keeps OpenAPI docs. The compose and kind env names are fixed in the Origins table.

## Routing

| Path | dev (Vite proxy) | compose e2e (`tests/e2e/gateway.conf`) | kind (HTTPRoute `shop`) |
|---|---|---|---|
| `/api` (existing) | yes | yes | yes |
| `/oauth2/` (prefix) | add | add `location /oauth2/` | add PathPrefix |
| `/login/oauth2/` (prefix) | add | add | add PathPrefix |
| `/logout` (exact) | add `'^/logout$'` | add `location = /logout` | add `Exact` |
| `/logout/connect/back-channel/...` | not routed | not routed | not routed (Keycloak calls `api:8080` directly) |
| Keycloak `/realms/shop/`, `/resources/` | direct `127.0.0.1:8180` | second server `listen 8089`, all else 404 | new listener `idp` (8089), HTTPRoute `keycloak`, all else 404 |

The SPA must not define routes under `/oauth2`, `/login/oauth2` or `/logout`; `/login` alone stays an SPA path (it 404s in the router). Vite keeps `changeOrigin: true`, which is harmless now that nothing derives URLs from `Host`.

## Kubernetes (`deploy/k8s/`, `deploy/kind/`)

- `deploy/kind/cluster.yaml`: add the mapping `containerPort 30089` to `hostPort 8089` on `127.0.0.1`. Existing clusters need `make cluster-down cluster-up`; `cluster-up` checks `docker port shop-control-plane 30089` and fails with that hint.
- `overlays/local/envoy-proxy.yaml`: add the NodePort mapping for Service port 8089 (`nodePort: 30089`). Base `gateway.yaml`: listener `idp` (HTTP 8089, `allowedRoutes Same`), HTTPRoute `keycloak` (`sectionName: idp`, PathPrefix `/realms/shop/` and `/resources/` to `keycloak:8080`), and the new API rules on `shop`.
- `keycloak.yaml` (base): Deployment and Service `keycloak` (port `http` 8080 only; management 9000 container-only), image `shop-keycloak` (renamed to `shop/keycloak:dev` in the overlay, `imagePullPolicy: Never`, `shop.dev/image-id: keycloak-image-id-placeholder`). `runAsUser: 1000`, restricted securityContext, `readOnlyRootFilesystem: true`, emptyDirs at `/tmp` and `/opt/keycloak/data/tmp`. Init container `import`: `kc.sh import --dir /opt/keycloak/data/import --override true --optimized` (D3), with the realm baked in and the users file from the overlay ConfigMap (generator, so a content change rolls the pod) mounted next to it. Main container `start --optimized`. Env: `KC_DB_URL=jdbc:postgresql://keycloak-db-rw:5432/keycloak`, credentials from `keycloak-db-app`, `KC_HOSTNAME=http://localhost:8089`, `KC_HTTP_ENABLED=true`, `KC_CACHE=local`, `SHOP_PUBLIC_ORIGIN`, `SHOP_AUTH_CLIENT_SECRET` from Secret `shop-auth`, test passwords from Secret `keycloak-test-users` (overlay only), `JAVA_OPTS_KC_HEAP=-XX:MaxRAMPercentage=65`. Resources: requests `cpu 250m, memory 768Mi`, limits `cpu 2, memory 1Gi`. Probes on 9000: startup `/health/started` (up to 4 min), liveness `/health/live`, readiness `/health/ready`.
- `keycloak-db.yaml`: CNPG `Cluster keycloak-db`, one instance, the same operand pin, `bootstrap.initdb {database: keycloak, owner: keycloak}`, 1Gi, `memory` 256Mi requested and 512Mi limit.
- `valkey.yaml`: Deployment and Service `valkey` (6379), `runAsUser: 999`, read-only root, emptyDir `/data`, `--save "" --appendonly no` (sessions are disposable; a restart signs everyone out, documented), `--requirepass` from Secret `valkey-auth` (passed through the env and `sh -c 'exec valkey-server ...'`), resources `10m/32Mi`, limit `128Mi`.
- `api.yaml`: env `SPRING_DATA_REDIS_HOST=valkey`, the password from `valkey-auth`, `SHOP_PUBLIC_ORIGIN`, `SHOP_AUTH_ISSUER`, `SHOP_AUTH_INTERNAL_REALM_URL`, `SHOP_AUTH_CLIENT_SECRET` (from `shop-auth`).
- Secrets `shop-auth`, `valkey-auth` and `keycloak-test-users` are not in Kustomize. `make deploy` creates them from `.cache/secrets/shop.env` (`kubectl create secret generic --dry-run=client -o yaml | kubectl apply --server-side -f -` inside the tools container with `.cache/secrets` mounted read-only; output to `/dev/null`). Diagnostics never read Secrets (unchanged rule).
- NetworkPolicies (selectors on `app.kubernetes.io/name`, as in slice 2):

| Name | Selector | Rule |
|---|---|---|
| `api-to-valkey` | api | egress TCP 6379 to valkey |
| `api-to-keycloak` | api | egress TCP 8080 to keycloak |
| `valkey-ingress` | valkey | ingress TCP 6379 from api |
| `keycloak-ingress` | keycloak | ingress TCP 8080 from the Envoy proxy pods (both owning-gateway labels) and from api |
| `keycloak-to-api` / `api-from-keycloak` | keycloak / api | back-channel logout, TCP 8080 |
| `keycloak-to-db` | keycloak | egress TCP 5432 to `cnpg.io/cluster: keycloak-db` |
| `keycloak-db-ingress` / `keycloak-db-egress` | `cnpg.io/cluster: keycloak-db` | as `db-ingress` / `db-egress` for `shop-db`, with keycloak instead of api |

- NetworkPolicy test (`tests/e2e/cluster/`): new probe pods `netpol-probe-keycloak` (name keycloak) and `netpol-probe-valkey`. **Allowed** (6): api to `shop-db-rw:5432`, api to `valkey:6379`, api to `keycloak:8080`, keycloak to `api:8080`, keycloak to `keycloak-db-rw:5432`, outside to `kube-dns:53`. **Denied** (16): the existing 7, plus web to valkey:6379, web to keycloak:8080, other to valkey:6379, keycloak to `shop-db-rw:5432`, keycloak to valkey:6379, api to `keycloak-db-rw:5432`, api to `keycloak:9000`, outside to `keycloak.shop:8080`, outside to `valkey.shop:6379`. The denied-means-exit-124 rule stays.

## Compose and platform

- `deploy/compose/compose.yaml` (dev): `keycloak` (`shop/keycloak:dev`, `start --optimized --import-realm`, `127.0.0.1:8180:8080`, `KC_HOSTNAME=http://localhost:8180`, `KC_BOOTSTRAP_ADMIN_USERNAME=admin` and its generated password for the dev console only, mounts the users file read-only into `data/import`), `keycloak-db` (postgres, `tmpfs`, so the realm is re-imported fresh on every start and never goes stale), `valkey` with `--requirepass`. The API gets `env_file: ../../.cache/secrets/shop.env` plus the dev URLs, and `depends_on` valkey.
- `tests/e2e/compose.yaml`: the same three services without published ports and without a bootstrap admin (`KC_HOSTNAME=http://localhost:8089`). The gateway waits for Keycloak healthy; the runner gets `E2E_IDP_UPSTREAM=gateway:8089` and the test passwords from the env file.
- Makefile: `secrets` (idempotent; `umask 077`; values from `head -c 32 /dev/urandom | base64 | tr -dc A-Za-z0-9`; never echoed), a dependency of `dev`, `e2e` and `deploy`. `keycloak-build` added to `build`. `MVN_IT_DOCKER` passes `VALKEY_IMAGE`. `deploy-apply` loads and annotates the third image and creates the Secrets. `cluster-e2e` passes `E2E_IDP_UPSTREAM=shop-control-plane:30089`. `scan-keycloak` produces a report only (D5). `versions.mk` gets `KEYCLOAK_IMAGE` with `# renovate: datasource=docker depName=quay.io/keycloak/keycloak`. quay.io gives no release timestamps, so the 7-day gate holds Keycloak updates (slice 2 B7); say so in the Renovate rule description and add Keycloak to the `cluster platform` group.
- CI: nothing new beyond what the make targets do. Both jobs generate fresh secrets per run.

## Web (`apps/web`)

- `src/api/session.ts`: `meQuery` (`/api/me`, `staleTime` 30 s, refetch on focus). `src/lib/csrf.ts`: read `__Host-XSRF-TOKEN` from `document.cookie`. An openapi-fetch middleware in `client.ts` adds `X-XSRF-TOKEN` on non-GET requests; on a 401 it invalidates `meQuery`.
- `SiteHeader`: a "Sign in" plain `<a href="/oauth2/authorization/keycloak">` (a full navigation, not a router link). Before it navigates, it saves the current path in `sessionStorage['shop.returnTo']`. After login, `/` restores it only if it starts with `/` and not `//` or `/\`. Signed in: name, an "Admin" link for admins, and "Sign out", which POSTs `/logout` then `window.location.assign(redirectUrl)`. `?login=failed` shows an alert.
- Routes: `admin.tsx` (layout; `beforeLoad` ensures `meQuery`; anonymous shows a sign-in prompt, customer shows "You do not have access"; the server enforces regardless), `admin.index.tsx` (product table: search, paging, active badge, stock), `admin.products.new.tsx`, `admin.products.$id.tsx` (product form plus stock form), `admin.audit.tsx`. Forms use TanStack Form with Zod schemas in `src/lib/adminSchemas.ts` that mirror the server rules. Server 400 `errors` map onto fields; 409 shows "Changed by someone else; reload". Mutations invalidate the admin and catalog queries.
- CSP: no change. Sign-in and sign-out are top-level navigations, not governed by `connect-src` or `form-action`, and admin forms submit through `fetch`. Every Zod use must go through the module that sets `z.config({ jitless: true })` (move it to `src/lib/zod.ts`, imported by `main.tsx`), or Zod's eval probe breaks the CSP (slice 1).
- Vite proxy entries as in Routing. 100% coverage including the returnTo validation, the CSRF middleware and every admin state.

## Tests

- **IdP in API tests: a stub, not a Keycloak container.** `support/StubIdentityProvider` (JDK `HttpServer`, Nimbus from `spring-security-oauth2-jose` for keys and signing) serves the JWKS and the token endpoint and signs ID and logout tokens with any claims. That makes the negative cases possible (wrong `iss`, `aud`, expired, `alg: none`, bad signature, logout token with `nonce` or without `events`), keeps tests fast and deterministic, and adds no dependency. Real Keycloak behaviour (realm import, issuer, PKCE enforcement, cookies over http) is proved by Playwright in both stacks, where it actually runs. `testcontainers-keycloak` would add about 20 s and 700 MB per run and still could not forge negative tokens. `IntegrationTest` also imports a Valkey container (`VALKEY_IMAGE`).
- API ITs: `OidcLoginIT` (authorization redirect has `code_challenge_method=S256`, `state`, `nonce` and the exact `redirect_uri`; callback with a stub code; the session id changes; `Set-Cookie` attributes; roles mapped; unknown roles dropped; each bad-token case fails to `/?login=failed`). `LogoutIT` (no CSRF gives 403; with CSRF, 200 JSON with `id_token_hint` and the public post-logout URI; session gone from Valkey). `BackChannelLogoutIT` (by `sid`, by `sub`, every invalid-token case leaves the session alive; it is the post-rotation session that dies). `SessionTimeoutIT` (absolute via `Clock`, idle via a short timeout in its own context). `AuthorizationMatrixIT` (every endpoint for anonymous, customer and admin, with and without CSRF, Problem Details bodies). `AdminProductIT`, `StockIT`, `AuditIT` (validation, 409s, audit row per change and none on a failed change, inactive products hidden from the public catalog). `ValkeyOutageIT` or a handler unit test for the 503. Unit tests for the role mapper, the registry, the handlers and `AdminEndpointsAreProtectedTest`. Existing ITs keep passing (management port, cache headers, nosniff).
- E2E (both stacks; `support/auth.ts` logs in through the Keycloak form with the generated passwords): `auth.spec.ts` (sign in and out as customer; cookie attributes via `context.cookies()`; session cookie value changes at login; after sign-out a new sign-in shows the Keycloak form, proving the SSO session ended; `/admin` as anonymous and as customer; `returnTo`). `admin.spec.ts` (an admin creates a product with a unique slug, edits it, sets stock, deactivates it; the public catalog follows; audit rows appear; repeatable on the persistent kind DB because seed products are never edited). `csrf.spec.ts` (request fixture with the signed-in storage state: POST without the header gives 403 problem+json; anonymous admin GET gives 401). `keycloak.spec.ts` (issuer in discovery is `http://localhost:8089/realms/shop`; `/admin/` and `/realms/master/` give 404; the password grant is refused). CSP and axe specs add the signed-in header and the admin pages, counting violations on the app origin only. `routing.spec.ts`: new paths, D8, and the deferred nit (use `frontDoorOrigin`, not a hardcoded 8088). Playwright adds `MAP localhost:8089 ${E2E_IDP_UPSTREAM}` to the resolver rules.
- NetworkPolicy test as above. Manual: back-channel logout through the Keycloak account console ("sign out all devices"), Valkey restart signs everyone out, Keycloak down (catalog fine, sign-in shows the failure, existing sessions continue), the absolute timeout, a memory reading.

## Docs (DOCS)

- `docs/adr/0006-authentication-bff-keycloak.md`: BFF in the API versus tokens in the SPA; separate IdP origin (D1); the no-discovery split of public and internal endpoints; cookies and CSRF; timeouts; both logouts and the Valkey registry; Keycloak image, realm-as-code and the override import; secrets (D4); consequences (sessions lost on a Valkey restart; Keycloak SSO expiry does not end app sessions early).
- `docs/adr/0007-admin-product-and-stock.md`: the admin API, optimistic locking, the stock model now and in slice 4, the audit log, deny-by-default.
- `docs/threat-model.md` (new; master plan Docs row): an auth section with assets, trust boundaries (browser, gateway, API, Keycloak, Valkey, Postgres) and STRIDE threats with mitigations and residual risks (no rate limit until slice 5; `sslRequired: external`; JDK serialisation; ID token in the logout URL). Checkout and webhook sections arrive in slice 4.
- `README.md` (sign-in, test-user passwords in `.cache/secrets/shop.env`, ports 8180 and 8089, footprint, known limits), `tests/README.md` (new rows and counts), `tests/manual/README.md`.

## Footprint (WSL, 12 GB limit)

Keycloak needs about 600 to 750 MB of RSS per instance. Added: dev about +0.8 GB (Keycloak, its Postgres), e2e about +0.9 GB while it runs, kind about +1.3 GB (Keycloak with its 1 Gi limit, `keycloak-db`, Valkey) on top of slice 2's 2.5 GB. Running `make dev`, `make e2e` and the cluster together approaches 8 GB; the README advises against it. The K8S agent measures the numbers and records them in the README.

## Work split (Sonnet implementers; no shared files)

| Phase | Agent | Owns |
|---|---|---|
| 1 | API | `services/api/**`. It commits the contract first: `make api-spec` output `openapi.json` with the endpoints above, before the rest of the work |
| 1 | PLATFORM | `deploy/keycloak/**`, `Makefile`, `deploy/versions.mk`, `deploy/compose/compose.yaml`, `renovate.json`, `.github/workflows/ci.yaml` (only if needed) |
| 1 | K8S | `deploy/k8s/**` (including `overlays/local/keycloak/shop-users-0.json`), `deploy/kind/**`, `tests/e2e/cluster/**` |
| 1 | DOCS | `docs/adr/0006*`, `docs/adr/0007*`, `docs/threat-model.md`; drafts from this brief, finalised in phase 3 |
| 2 (after the API contract) | WEB | `apps/web/**` except generated files (it runs `make web-api-client` and `generate:routes`) |
| 2 (after PLATFORM's compose and secrets) | E2E | `tests/e2e/**` except `cluster/` (specs, `playwright.config.ts`, `compose.yaml`, `gateway.conf`) |
| 3 | DOCS | `README.md`, `tests/README.md`, `tests/manual/README.md` with the measured numbers |

Fixed interfaces: the hosts and ports in the Origins table; env names `SHOP_PUBLIC_ORIGIN`, `SHOP_AUTH_ISSUER`, `SHOP_AUTH_INTERNAL_REALM_URL`, `SHOP_AUTH_CLIENT_ID` (`shop-api`), `SHOP_AUTH_CLIENT_SECRET`, `VALKEY_PASSWORD`, `KEYCLOAK_DB_PASSWORD`, `KC_BOOTSTRAP_ADMIN_PASSWORD`, `SHOP_TEST_CUSTOMER_PASSWORD`, `SHOP_TEST_ADMIN_PASSWORD`, `E2E_IDP_UPSTREAM`; file `.cache/secrets/shop.env`; realm `shop`, registration id `keycloak`, roles `customer` and `admin`, claim `roles`; cookies `__Host-SESSION` and `__Host-XSRF-TOKEN`, header `X-XSRF-TOKEN`; paths in the Routing table; the logout JSON `{"redirectUrl"}`; images `shop/keycloak:dev`; objects `keycloak`, `keycloak-db` (`keycloak-db-rw`, `keycloak-db-app`), `valkey`, Secrets `shop-auth`, `valkey-auth`, `keycloak-test-users`, listener `idp`, HTTPRoute `keycloak`, placeholder `keycloak-image-id-placeholder`; test users `customer@example.test`, `admin@example.test`.

Each agent verifies its part in containers (API: `make api-lint api-test`; WEB: `make web-lint web-test`; PLATFORM: `make lint build secrets`, then `make dev` and a manual sign-in through 5173; K8S: kustomize plus kubeconform, then `make cluster-up deploy cluster-e2e`; E2E: `make e2e-lint e2e`). The orchestrator then runs `make generate` (no diff), `make ci`, `make cluster-down cluster-up deploy cluster-e2e`, a second `make deploy` (no restarts) and `make dev`.

## Out of scope

Cart and checkout, customer self-registration and profile pages, rate limiting (slice 5), TLS and HSTS, Keycloak themes, MFA, a Keycloak operator, multiple Keycloak or Valkey replicas, category management and product images, a cloud overlay, using the access token against other services.

## Known traps

- CLAUDE.md traps apply: Jackson 3 is `tools.jackson.*` (Problem Details writers in the security handlers too); `RestClient` only; Testcontainers 2 names; no Bitnami; check Vite 8 compatibility (no new Vite plugins here); `typescript` alias; `pnpm_config_*`; the CSP shows up only in `make e2e`; cgroup v1 locally.
- **Issuer split** (above): never set `spring.security.oauth2.client.provider.*.issuer-uri`; it triggers discovery against the browser URL.
- **Spring Security 7 changes existing behaviour**: it authorises every dispatcher type, so permit the error dispatch or 400 and 404 become 401. The management port shares the chain, so permit health or the kubelet probes fail. Headers: `X-Frame-Options`, `no-store`, `nosniff` now come from Spring. `/api/**` becomes deny-by-default (D8).
- **CSRF**: `csrf.spa()` must come before `csrfTokenRepository(...)`, because the later call wins. Login and logout clear the token, and the SPA must read the cookie fresh before each unsafe request, never cache it. Plain `fetch` sends no header; only the openapi-fetch client adds it.
- **`__Host-` cookies** require `Secure`, `Path=/` and no `Domain`. Spring sets `Secure` from `request.isSecure()` unless it is forced on, and behind every proxy here the request is http, so force it on both cookies. Chromium accepts them because the origin is `localhost`; the Node request context in Playwright needs the storage state from a browser context to carry them.
- **Back-channel logout**: the default handler re-POSTs `/logout` to `localhost` with a `JSESSIONID` cookie; do not use it. The default registry is in memory. Keycloak sends logout tokens with `sid`, which needs `backchannel.logout.session.required`.
- **Keycloak**: `--import-realm` skips an existing realm (hence tmpfs DBs in compose and `import --override` in kind). Placeholders are `${VAR}` from the environment. Users files must be named `shop-users-0.json`. An optimised image is needed for a read-only root. The image has no curl or wget. `KC_CACHE=local` avoids JGroups. Admin and master realm paths stay unrouted. On plain http Keycloak's own cookies rely on the localhost secure-context exception; check in e2e that its login form works in both stacks.
- **Kustomize** refuses files outside the kustomization root, hence the users file under `overlays/local`. ConfigMap generators add a hash suffix, so reference them by base name.
- **Envoy Gateway**: a second listener needs its own NodePort in the `EnvoyProxy` patch (Service port 8089); route `/logout` with `Exact` so `/logout/connect/...` stays internal.
- **Playwright**: the resolver rules take a comma-separated list; the `request` fixture bypasses them (B2), so Keycloak calls from Node need their own context with `Host: localhost:8089`.
- **Admin e2e on kind** runs against a persistent database: use unique slugs, never edit seed products, and do not assert exact counts.
- **Spring Session**: `spring.session.timeout` wins over `server.servlet.session.timeout`; verify every property name in the Boot 4.1.2 appendix, including `server.servlet.session.cookie.same-site` reaching Spring Session's `DefaultCookieSerializer`.

## Top risks

1. Issuer and hostname consistency across three stacks: caught by `OidcLoginIT` (iss check) and `keycloak.spec.ts` in both stacks.
2. Keycloak with a read-only root, the optimised image and the override import init container in kind: verify early (K8S agent's first task).
3. Back-channel registry holding the pre-rotation session id: `BackChannelLogoutIT` decides.
4. Memory on WSL with three Keycloaks running; CI runtime (Keycloak start of about 30 to 60 s in each e2e stack).
5. Flaky Keycloak form selectors in e2e: use label-based locators (`Email`, `Password`, `Sign In`) and keep one helper.

## Amendments (2026-11-17; override the body above)

Sources: general challenger (G), security and failure-mode challenger (S), owner decisions (O). Rationale for the owner decisions: https://claude.ai/artifact/5UfXP9VaCRhDCzmkWAZJcM.

### Owner decisions (final)

- **C1 (O, D13): two PRs.** **3a** on `slice-3-auth`: Keycloak in all three stacks, BFF login and both logouts, sessions, CSRF, timeouts, revalidation (C2), `/api/me`, deny-by-default, one admin read endpoint and a read-only admin page. **3b** on a new branch after 3a merges: admin writes, stock, audit, admin forms, V3 to V5 migrations, ADR 0007. Everything in this brief about writes, stock, audit, `admin.products.*`, `admin.audit.tsx`, `AdminProductIT`, `StockIT`, `AuditIT` and ADR 0007 moves to 3b. 3a scope details in C40.
- **C2 (O, D11; S1 blocker): revalidate with Keycloak.** Keycloak's 30-min SSO idle would expire while the app session lives on, so back-channel logout and role changes stop reaching the app. On each authenticated request, if `now - lastRevalidatedAt > shop.auth.revalidate-after` (5 min, injectable `Clock`), refresh through `OAuth2AuthorizedClientManager` (refresh-token grant), re-map roles from the new ID token, rebuild the `Authentication`, store `lastRevalidatedAt`. On `invalid_grant` or a disabled user: invalidate the session and clear the context (fail closed, as C4). Keycloak unreachable or 5xx: keep the session until `revalidate-grace` (15 min since the last success), then fail closed. Single flight per session: a Valkey lock `shop:revalidate:{sessionId}` (SET NX, 10 s TTL); a request that loses the race proceeds with the current authentication. Keep `revokeRefreshToken: true`. Keep the default session-backed `OAuth2AuthorizedClientRepository` (the refresh token is needed). Realm: keep `ssoSessionIdleTimeout: 1800`, `ssoSessionMaxLifespan: 28800`; leave the client-session timeouts unset so they inherit the realm values. Tests: `RevalidationIT` (stub IdP): refresh after 5 min re-maps roles (admin demoted to customer gets 403 on the next request), `invalid_grant` gives 401 on the same request, IdP down within grace keeps the session, after grace gives 401, two concurrent requests trigger one refresh.
- **C3 (O, D3; G9, G8, S11):** D3 stays (CNPG `keycloak-db`, `import --override true` init container). The Keycloak Deployment uses `strategy: Recreate` (Keycloak requires all nodes stopped for an override import). ADR 0006 records that each restart re-creates the realm (new signing keys, SSO sessions lost); app sessions still end at sign-out because the app invalidates before redirecting. The SPA tolerates Keycloak's error page at the end of logout. Manual check: restart Keycloak, then sign out.
- **C4 (O, D4; S7, S8): secrets.** Every Keycloak entrypoint (compose command, kind init and main containers) is wrapped as `bash -c ': "${SHOP_AUTH_CLIENT_SECRET:?}" "${SHOP_PUBLIC_ORIGIN:?}" ...; exec /opt/keycloak/bin/kc.sh ...'` for every placeholder it imports (test-user passwords only where the users file is mounted). `make secrets` is idempotent, adds missing keys to an existing file, and fails if any value is shorter than 32 characters. Compose: no `env_file`; `docker compose --env-file .cache/secrets/shop.env` and each service maps only its own variables (`VAR: ${VAR:?}`): the API gets the client secret and Valkey password; Keycloak gets the client secret, its DB password, the bootstrap admin password (dev only) and the test passwords; the e2e runner gets only the two test passwords. Never use the repo root as a Docker build context.
- **C5 (O, D5; S):** `scan-keycloak` fails on fixable CRITICAL; HIGH is reported (JSON and SARIF in `build/reports/trivy/`). Exceptions go in `deploy/keycloak/.trivyignore.yaml` with an `expired_at` per entry. Gate HIGH in slice 6. CI uploads the SARIF with the existing public-repo guard. The Dockerfile's final stage sets `USER 1000` (G, scan-fs misconfig).
- **C6 (O, D8; S5, G3):** unknown `/api` paths give 401 to anonymous callers and 403 to authenticated ones (`denyAll`). `/v3/api-docs/**` is permitted only when `springdoc.api-docs.enabled` is true (the `dev` profile and `OpenApiContractIT`, which `make api-spec` runs); in the default profile the path is not permitted and a test asserts 401. `EndpointRequest.toLinks()` is permitted on the management port. Expected changes: `ManagementEndpointsIT` (app-port actuator paths become 401, not 404), `routing.spec.ts`. An anonymous unsafe request without a CSRF token gets 403 (CSRF problem), not 401; `AuthorizationMatrixIT` states this.
- **C7 (O, D9; S): session deserialisation.** Keep JDK serialisation, with a JEP 290 `ObjectInputFilter` allowlist (`java.*` value types used by the session, `org.springframework.security.**`, `org.springframework.session.**`, `dev.shop.api.**`; reject everything else, `maxdepth` 20) on the session `RedisSerializer`. A `SessionRepository` decorator catches `SerializationException` only, deletes the session and returns null (anonymous). Unit test: a session holding a non-allowlisted class is rejected and deleted.
- **C8 (O, D12):** Renovate rule scoped to `quay.io/keycloak/keycloak`: `minimumReleaseAgeBehaviour: timestamp-optional` (as for the Playwright image), in the `cluster platform` group. Remove the "7-day gate holds Keycloak updates" sentence.
- **C9 (O, D1; G11):** D1 rationale corrected: cookies are not port-scoped, so `__Host-SESSION` and `__Host-XSRF-TOKEN` from `localhost:8088` are also sent to `localhost:8089`, and the XSRF cookie is readable by scripts there. Origin isolation (DOM, CSP) still holds. Cookie separation is real only with distinct hostnames (cloud). Threat model lists this as a local residual risk.
- D2, D6 (moved to 3b; admin `size` at most 100, `page` at most 10 000), D7 (also `editUsernameAllowed: false`) and D10 as recommended.

### Security and correctness (API)

- **C10 (S2, G5): no anonymous sessions.** `requestCache(c -> c.requestCache(new NullRequestCache()))`. The session created by `/oauth2/authorization/**` gets `maxInactiveInterval` 5 min; the success handler sets 30 min after login. Valkey runs with `--maxmemory 96mb --maxmemory-policy volatile-lru` (kind; compose the same). IT: anonymous 401, 403 and 404 responses carry no `Set-Cookie: __Host-SESSION`. Threat model: session flooding until rate limits in slice 5.
- **C11 (S3): absolute timeout.** `AbsoluteSessionTimeoutFilter` also calls `securityContextHolderStrategy.clearContext()` and removes the CSRF token, so the expiring request itself runs anonymously. Authenticated session without `authenticatedAt` is treated as expired. `SessionTimeoutIT` asserts the expiring request gets 401 on an admin GET. Idle timeout is asserted through the session key's `PTTL` (about 1800 s) in the shared context, not a separate context (G16).
- **C12 (S4): method-security denials.** `ApiExceptionHandler` rethrows `AccessDeniedException` and `AuthenticationException` (or maps them to 403 and 401 Problem Details explicitly) instead of the catch-all 500. Test: call an admin controller with a customer authentication, bypassing the URL rule, and assert 403 problem+json.
- **C13 (S6, G6): Valkey and Keycloak failures.** A servlet filter ordered before `SessionRepositoryFilter` (order lower than `SessionRepositoryFilter.DEFAULT_ORDER`) maps `RedisConnectionFailureException` and command timeouts to a 503 problem+json. Public GETs during a Valkey outage: a request without a session cookie never touches Valkey and stays 200; a request with a cookie gets 503 (accepted; documented). `spring.data.redis.timeout: 2s`, `connect-timeout: 2s`. The `RestClient` behind the token response client and the JWKS decoders uses 2 s connect and 5 s read timeouts. `ValkeyOutageIT` is a real IT that stops the Valkey container: public GET without cookie 200, with cookie 503 problem+json.
- **C14 (S9): back-channel registry contract.** `ValkeyOidcSessionRegistry` stores only `{sessionId, sub, sid, iss}` (never the CSRF token, cookie or ID token). Logout tokens match on `sid` when present, and the stored `sub` and `iss` must match too; `sub`-only when `sid` is absent. Mappings are removed on RP-initiated logout and on the absolute timeout; TTL covers the rest. Do not rely on `SessionDestroyedEvent`. `BackChannelLogoutIT` adds: a `sid` from another user leaves that session alive; a token signed by an unknown key leaves the session alive.
- **C15 (S10): forwarded headers.** The compose e2e gateway sets `proxy_set_header X-Forwarded-For $remote_addr; X-Forwarded-Proto $scheme;` and clears `X-Forwarded-Host`. For Envoy Gateway, a `ClientTrafficPolicy` with `numTrustedHops: 0` on the gateway (or confirm and document that the default overwrites them). Any `Location` header is built from the path or `SHOP_PUBLIC_ORIGIN`. IT: a spoofed `X-Forwarded-Host` does not appear in the logout `redirectUrl` or any `Location`.
- **C16 (S12): logs.** Gateway nginx and Envoy access logs do not record query strings on `/login/oauth2/` and `/realms/shop/protocol/openid-connect/` (codes and `id_token_hint`). Threat model lists it.
- **C17 (S14): Keycloak surface.** Do not route `/realms/shop/clients-registrations/`. Client `shop-api` sets `standard.token.exchange.enabled: false`. `keycloak.spec.ts` asserts client registration is not reachable and token exchange and device flow are refused.
- **C18 (S15): identity.** Audit and authorisation use `sub` only; `name` and `email` are display data the user can edit in the account console.
- **C19 (S16): secrets in config.** `ShopAuthProperties` overrides `toString` to redact the client secret. Secrets use `@NotBlank` only (no patterns that would echo the value in a bind-validation failure).
- **C20 (S18, S19, S20): residual risks recorded in the threat model:** no `jti` replay check on logout tokens (replay can only sign out again); brute-force lockout per user sees only the gateway IP, so anyone can lock out the admin account until slice 5; Playwright traces on failure record typed test passwords (per-run secrets limit the impact; noted in `tests/README.md`).
- **C21 (G4): `@WebMvcTest` slices.** Existing slice tests (`CatalogControllerTest`, `StatusControllerTest`) import `SecurityConfiguration` with a test `ClientRegistrationRepository` and assert anonymous access to public endpoints; pure controller tests may use `@AutoConfigureMockMvc(addFilters = false)` only where security is covered elsewhere.
- **C22 (G7): sign-out CSRF.** `/logout` is not in the OpenAPI document, so the generated client never calls it. The sign-out call uses `src/lib/csrf.ts` explicitly; a web unit test covers it.
- **C23 (G8): `id_token_hint`.** Verify (e2e, both stacks) that Keycloak 26.8 accepts an expired hint (sign in, wait past `accessTokenLifespan` using a short lifespan in the e2e realm overrides if needed, sign out). If it rejects it, fall back to `client_id` plus `post_logout_redirect_uri` without the hint (Keycloak shows its confirm page) and record it in ADR 0006.
- **C24 (G17): CSRF cookie freshness.** IT: the first GET after login and after logout returns a fresh `__Host-XSRF-TOKEN`.
- **C25 (G13): starter names.** Use `spring-boot-starter-security-oauth2-client`; tests `spring-boot-starter-security-test`, `spring-boot-starter-security-oauth2-client-test`, `spring-boot-starter-session-data-redis-test`. `spring-boot-starter-session-data-redis` as stated.
- **C26 (G18): modules.** New `auth` module (security config, `ShopAuthProperties`, client registration, registry, logout handlers, timeout and revalidation filters, Valkey failure filter). `web` keeps cross-cutting HTTP (exception handler, request filter, OpenAPI). `customer` holds `/api/me`, `ShopRoles`, `CurrentUser`. Declare `allowedDependencies` in each new `package-info.java`.
- **C27 (G19):** ADR 0006 records that only `/login/oauth2/` (not `/login`) is routed to the API, a deliberate deviation from the master plan's Routing row.
- **C28 (S13): `returnTo`.** Parse with `new URL(v, location.origin)`, require the same origin, cap at 2000 characters, navigate through the router with `pathname + search + hash`, never `location.assign`.

### Platform, Kubernetes and tests

- **C29 (G1 blocker): file ownership.** PLATFORM owns every edit to `Makefile`, `deploy/versions.mk`, `.github/workflows/ci.yaml`, `deploy/k8s-tools/diagnostics.sh`, `renovate.json`, `deploy/compose/compose.yaml` and `deploy/keycloak/**`, and also `deploy/k8s/overlays/local/keycloak/shop-users-0.json` (the one users file). PLATFORM runs first and delivers the Makefile interface in C30; K8S and E2E then use it without editing the Makefile. If the Makefile would pass 500 lines, PLATFORM moves the cluster targets to `deploy/cluster.mk` (included).
- **C30: Makefile interface (PLATFORM).** `secrets` (C4); `keycloak-build` in `build` and a prerequisite of `dev`, `e2e` and `deploy` (G14); `scan-keycloak` in `scan` (C5); `MVN_IT_DOCKER` passes `VALKEY_IMAGE`; `dev`, `e2e` and `cluster-e2e` call `docker compose --env-file .cache/secrets/shop.env`; `e2e` and `cluster-e2e` pass `E2E_IDP_UPSTREAM` (`gateway:8089` and `shop-control-plane:30089`) and only the two test passwords to the runner; `cluster-up` checks `docker port shop-control-plane 30089` and fails with the `make cluster-down cluster-up` hint; `deploy-apply` loads and annotates `shop/keycloak:dev`, creates the Secrets `shop-auth`, `valkey-auth`, `keycloak-test-users` (output to `/dev/null`), waits for `keycloak-db`, then rolls out `valkey` and `keycloak` before `api`; `docker-lint` covers `deploy/keycloak/Dockerfile`; `diagnostics.sh` adds keycloak, valkey and keycloak-db pods (no Secrets); `ci.yaml` adds the Keycloak SARIF upload. `cluster-up` restarting a stopped node polls `kubectl get --raw /readyz` (bounded) after `docker start` (slice 2 deferred nit).
- **C31 (G15):** `KC_HOSTNAME`, `SHOP_PUBLIC_ORIGIN`, `SHOP_AUTH_ISSUER` and `SHOP_AUTH_INTERNAL_REALM_URL` live in `overlays/local`, not in base manifests.
- **C32 (G20):** K8S's first task also checks whether Keycloak writes `/opt/keycloak/data/transaction-logs` (or anything else) at startup with a read-only root, and adds an emptyDir if so.
- **C33 (G10): real back-channel logout in e2e (both stacks).** Sign in; in the same context open `http://localhost:8089/realms/shop/protocol/openid-connect/logout` and confirm (IdP-initiated); assert `/api/me` returns `authenticated: false`.
- **C34 (S, D6):** admin list endpoints bound `size` at most 100 and `page` at most 10 000.
- **C35:** e2e realm differences (short `accessTokenLifespan` for C23, if used) are passed as environment placeholders, never a second realm file.
- **C36:** the API agent work is split: **API-AUTH** (`auth/`, `customer/`, `web/` changes, `application.yaml`, `test/.../support/`, security ITs) and, in 3b, **API-ADMIN**. In 3a there is one API agent.
- **C37:** `tests/e2e/specs/routing.spec.ts:7` uses `frontDoorOrigin` (slice 2 deferred nit), owned by E2E.
- **C38:** CI time after the first run is recorded in STATUS (Keycloak image build, two Keycloak boots in kind, scan).
- **C39:** the NetworkPolicy matrix in the body is for the full slice; 3a needs all of it (Keycloak, keycloak-db and Valkey all arrive in 3a).

### C40: 3a scope and work split

**In 3a:** everything in Origins, Keycloak, the security filter chain, session, CSRF, both logouts, Problem Details (401, 403, 403 CSRF, 503), routing, Kubernetes, compose and platform, plus amendments C2 to C39. Endpoints: `GET /api/me` and `GET /api/admin/products?q&page&size` returning `AdminProductPage` of `AdminProduct {id, slug, name, description, price: Money, category: Category, active}` (inactive included, sorted by name; no `version` or `stock` until 3b). No migrations. Web: sign in and out, header states, `returnTo`, `?login=failed`, `admin.tsx` layout (anonymous prompt, customer "no access"), `admin.index.tsx` read-only product table with search and paging. Zod config moves to `src/lib/zod.ts`. No TanStack Form yet (3b). E2E: `auth.spec.ts`, `csrf.spec.ts` (POST to `/logout` without the header, and to an admin path, give 403), `keycloak.spec.ts`, C33, the read-only admin page for anonymous, customer and admin, CSP and axe on the signed-in header and admin page, `routing.spec.ts`. Docs: ADR 0006, threat model (auth section), README, tests/README, manual checklist.

**Not in 3a:** product and stock writes, audit, V3 to V5, `admin.products.*`, `admin.audit.tsx`, `adminSchemas.ts`, TanStack Form, ADR 0007, `AdminProductIT`, `StockIT`, `AuditIT`, `admin.spec.ts` writes.

| Phase | Agent | Owns |
|---|---|---|
| 1 | PLATFORM | C29 files; delivers C30 and `make secrets keycloak-build` first |
| 1 | API | `services/api/**`; publishes `openapi.json` (`make api-spec`) as soon as `/api/me` and the admin read endpoint compile |
| 1 | DOCS | `docs/adr/0006*`, `docs/threat-model.md` drafts |
| 2 (after PLATFORM) | K8S | `deploy/k8s/**` except the users file, `deploy/kind/**`, `tests/e2e/cluster/**` |
| 2 (after the API contract) | WEB | `apps/web/**` except generated files |
| 2 (after PLATFORM) | E2E | `tests/e2e/**` except `cluster/` |
| 3 | DOCS | `README.md`, `tests/README.md`, `tests/manual/README.md` with measured numbers |
