# shop

Spring Boot API (`services/api`) and React web app (`apps/web`). Visitors can browse a seeded product catalog: search, filter by category, sort, page through results and open a product page. Users sign in through Keycloak; customers register, fill a cart, check out, pay on a fake payment page and get a confirmation email; admins manage products and stock and read an audit log. Every tool runs in a pinned container; `make` is the only entry point, locally and in CI.

## Prerequisites

- Docker (29.x) with Compose v5
- GNU make
- Your user in the `docker` group (or equivalent access to the Docker socket)

No Java or Node is needed on the host.

## Quick start

```
make secrets  # once per checkout; make dev, e2e and deploy also run it
make dev      # Postgres, Valkey, Keycloak and its database, Mailpit, API, web dev server
make logs     # follow startup; the first API start downloads Maven dependencies and is slow
make down
```

- Web: http://localhost:5173 (proxies `/api` to the API container)
- Keycloak: http://localhost:8180 (realm `shop`; the admin console is available there with user `admin` and `KC_BOOTSTRAP_ADMIN_PASSWORD` from `.cache/secrets/shop.env`)
- Mailpit UI: http://localhost:8025
- Postgres: `localhost:5432`, database and user `shop`, password `dev-only-postgres-password` (local dev only)
- With `make dev OBS=1` the stack also runs `grafana/otel-lgtm` and the API exports traces, metrics and logs to it: Grafana on http://localhost:3000 (anonymous Viewer; sign in as `admin`, otel-lgtm's built-in login, to use Explore; dashboard "Shop API"). `make obs-check-dev` checks that every dashboard metric arrived. `make down` removes the extra container. Without `OBS=1` nothing is exported.

The web app shows an error until the API has finished starting. Keycloak imports the realm on every start, so sign-in works a minute or so after the stack is up. `make dev` runs the API with the Spring profiles `dev` and `seed`.

## Sign in

Use "Sign in" in the header. Keycloak shows its own login form on its own origin: `http://localhost:8180` in `make dev`, `http://localhost:8089` in the e2e stack and kind. The browser holds only an HttpOnly `__Host-SESSION` cookie; tokens stay in the API and Valkey (`docs/adr/0006-authentication-bff-keycloak.md`).

| User | Role | Password |
|---|---|---|
| `customer@example.test` | customer | `SHOP_TEST_CUSTOMER_PASSWORD` in `.cache/secrets/shop.env` |
| `admin@example.test` | admin | `SHOP_TEST_ADMIN_PASSWORD` in the same file |

`make secrets` generates `.cache/secrets/shop.env` (mode 600) for this checkout: client secret, Valkey and Keycloak database passwords, the Keycloak bootstrap admin password, the two test-user passwords and the payment webhook secret (`SHOP_PAYMENT_WEBHOOK_SECRET`). On a checkout that existed before slice 4b, run `make secrets` once to add the new key; `make dev`, `make e2e` and `make deploy` also run it. It is idempotent, adds missing keys and keeps existing ones, and the file is gitignored. Never commit it or paste its contents anywhere. The test users exist only in a local overlay file, never in the Keycloak image. Sessions last 30 minutes idle and 8 hours absolute.

### Register

Anyone can register from the sign-in page (`Register`) with a username, an email address, a first and a last name. There is no password field on that form: Keycloak 26.8 hides it while email verification is on, so the new user gets an email, follows its link and sets the password there, and is then signed in. Until then the account cannot sign in. New accounts get the `customer` role. In `make dev` the email is in Mailpit (http://localhost:8025); in the cluster it is in Mailpit on http://localhost:8026. Order mail goes only to a verified address. Why: `docs/adr/0009-payments-webhook-and-outbox.md`.

## Run on Kubernetes (kind)

```
make cluster-up deploy   # kind cluster, Calico, Envoy Gateway, Postgres operator, then the app
make cluster-e2e         # front-door smoke check, Playwright suite, NetworkPolicy test
make kubectl ARGS="-n shop get pods"
make cluster-down
```

Open http://localhost:8088 for the seeded catalog; Keycloak is on http://localhost:8089 and Mailpit on http://localhost:8026. An existing cluster lacks the 8089 and 8026 mappings: run `make cluster-down cluster-up` once (the first recreate wipes the kind database; `make cluster-up` on an old cluster prints a warning when the Mailpit mapping is missing). Keycloak imports its realm in an init container (about 90 s) and is ready 2 to 3 minutes after its pod starts, so the first `make deploy` takes a few minutes longer. Running `make deploy` again with nothing changed restarts no pods. The first run downloads about 1.5 GB of images. With everything running the cluster node uses about 3.5 GiB (was 2.5 before Keycloak, its database and Valkey; measured with `docker stats shop-control-plane --no-stream`). Keycloak alone needs 600 to 750 MB per instance; `make dev`, `make e2e` and the cluster together approach the 8 GB mark, so do not run all three at once. Why it is built this way: `docs/adr/0005-local-kubernetes-on-kind.md`.

Since slice 5a the API runs under a HorizontalPodAutoscaler (2 to 4 replicas, 60% CPU) and the web app runs 2 replicas; both have a PodDisruptionBudget (`maxUnavailable: 1`). `make cluster-up` installs metrics-server, so `make kubectl ARGS="-n shop top pods"` works; a new pod shows `<unknown>` CPU for about 45 s, which is normal. `make deploy` waits for two ready API pods. `make cluster-e2e` also writes an HPA replica summary (`build/reports/cluster-e2e/hpa.log`) and runs the PDB eviction check (`pdb.log`). The HPA owns `replicas`: if you switch this cluster back to a branch from before 5a, `make deploy` fails with a field conflict, so recreate it with `make cluster-down cluster-up deploy`.

### Observability in kind

```
make obs-up                # Prometheus, Loki, Tempo, an OpenTelemetry Collector and Grafana in namespace obs
make deploy OVERLAY=obs    # the app with OTLP export on (plain make deploy exports nothing)
make obs-check             # every metric the dashboard uses is in Prometheus
make obs-down
```

Grafana is at http://grafana.localhost:8088 (anonymous Viewer, who cannot use Explore; sign in as `admin` with `GRAFANA_ADMIN_PASSWORD` from `.cache/secrets/shop.env`; after changing it, `make obs-up` rolls the Grafana pod through a checksum annotation), through the same Gateway as the shop; `http://localhost:8088` still serves the shop. No new port mapping or cluster recreate is needed. `obs-up` fails if the memory requests in `obs` exceed 1 GiB (observed: 816 MiB requested; at idle Grafana uses about 330 MiB and the rest 30 to 120 MiB each). Run `make obs-down` when done; an API deployed with `OVERLAY=obs` logs export errors while `obs` is gone until you run `make deploy`, which also removes the obs-only NetworkPolicy. Design and numbers: `docs/adr/0010-observability.md`.

Host facts for the numbers in these docs (2026-11-21): 15.6 GiB, 12 CPUs, cgroup v1, no `.wslconfig` (WSL's default of half the host memory applies). In kind the node used 3.7 GiB with two API pods before obs; in compose `otel-lgtm` used 1.07 GiB. CI is cgroup v2 and runs no obs stack.

## What the app does

Web routes:

| Route | Shows |
|---|---|
| `/` | Product catalog. Search, category, sort and page live in the URL (`?q=&category=&sort=&page=`), so views can be bookmarked and shared. `page` is 1-based in the URL |
| `/products/$slug` | One product |
| `/status` | API status and version |
| `/cart` | Customer: cart lines with quantity, line totals and availability |
| `/checkout` | Customer: review the cart and place the order |
| `/orders` | Customer: own orders, newest first, paged |
| `/orders/$id` | Customer: one order with status, `Reserved until`, snapshot unit price, line totals and `Cancel order` |
| `/admin` | Admin area. Anonymous visitors get a sign-in prompt, customers a "no access" panel, admins the product table (search and paging, inactive products included) with a link per product to its edit page |
| `/admin/products/new` | Admin only: create a product |
| `/admin/products/$id` | Admin only: edit a product (name, description, price, category, active) and set its stock |
| `/admin/audit` | Admin only: the audit log, newest first |

Sign-in and sign-out are not SPA routes: `/oauth2/authorization/keycloak` starts sign-in, `/login/oauth2/code/keycloak` is the callback and `POST /logout` ends the session. These paths, and `/login/oauth2/`, `/oauth2/` and exact `/logout`, are routed to the API. `/login` is an SPA path. Failed sign-in lands on `/?login=failed`.

API endpoints (all under `/api`, errors are RFC 9457 Problem Details):

| Endpoint | Purpose |
|---|---|
| `GET /api/categories` | All categories, ordered by name |
| `GET /api/products?q=&category=&sort=&page=&size=` | One page of products. `sort` is `relevance`, `name`, `price_asc` or `price_desc`; `page` is 0-based; `size` is 1 to 48 (default 12) |
| `GET /api/products/{slug}` | One product, or 404 |
| `GET /api/status` | Status and version |
| `GET /api/me` | Public. `{authenticated, ...}` with name, email and roles when signed in |
| `GET /api/admin/products?q=&page=&size=` | Admin only: products including inactive, sorted by name, each with `stock {onHand, reserved, version}` |
| `POST /api/admin/products` | Admin only: create a product (201, stock starts at 0) |
| `GET /api/admin/products/{id}`, `PUT /api/admin/products/{id}` | Admin only: read or update a product. The body carries the `version` you read; a stale one gets 409. The slug cannot change; `active` is part of the body |
| `PUT /api/admin/products/{id}/stock` | Admin only: set `onHand` (with the stock `version`); never below `reserved` |
| `GET /api/admin/audit?page=&size=` | Admin only: audit events, newest first |
| `GET /api/cart` | Customer: the cart (an absent cart is version 0 and empty) |
| `PUT /api/cart/lines/{slug}`, `DELETE /api/cart/lines/{slug}` | Customer: set a line's quantity (1 to 10, at most 20 lines; 404 for an unknown or inactive product) or remove it (always 200) |
| `POST /api/checkout` | Customer: needs an `Idempotency-Key` UUID header and `{cartVersion, expectedTotalMinor}`. 201 with the order and `Location`; a repeated key returns the order's current state with `Idempotency-Replayed: true` |
| `GET /api/orders?page=&size=`, `GET /api/orders/{id}` | Customer: own orders only (another customer's id is 404); `size` is 1 to 50 |
| `POST /api/orders/{id}/cancel` | Customer: cancel an unpaid order and release its stock |
| `POST /logout` | Signed-in users, CSRF token required. Ends the session and answers `{"redirectUrl"}` for Keycloak's end-session page |

Unsafe requests (POST, PUT, PATCH, DELETE) need the `X-XSRF-TOKEN` header matching the `__Host-XSRF-TOKEN` cookie, otherwise 403. Everything not listed is denied: an unknown `/api` path returns 401 to anonymous callers and 403 to signed-in ones (it used to be 404), and `/actuator/env` and `/actuator/info` on the management port return 401. Admin endpoints answer 401 to anonymous callers and 403 to customers, and send `Cache-Control: no-store`. Validation errors are 400 and conflicts 409 with `application/problem+json` and an `errors` array; a row lock held longer than 3 s gives 503 with `Retry-After: 3`. Admin writes also give 503 when the last successful check with Keycloak is older than 5 minutes (a demoted admin cannot keep writing while Keycloak is down), with `Retry-After: 300`; writes can stay at 503 for up to 5 minutes after Keycloak recovers.

The contract is committed as `services/api/openapi/openapi.json`; the design is in `docs/adr/0003-catalog-data-and-api.md`.

### Cart, checkout and orders

A signed-in customer (role `customer`) sets product quantities in a cart stored in Postgres (`Add to cart` sets the line's quantity, it does not add to what is already there; on the cart page, changing the quantity or `Remove` greys out and ignores further input while a change is pending, and after `Remove` focus moves to the `Cart` heading). Checkout re-prices the cart under locks, reserves stock for 15 minutes and creates an order in `PENDING_PAYMENT`, shown as `Awaiting payment`. The customer can pay (see below), cancel (`Cancelled`) or let the hold run out, after which a background job releases the stock (`Expired`). Unsafe cart and order calls need the CSRF header like all others. Anonymous visitors see `Sign in to buy` on product pages.

Rules and settings (`shop.checkout.*`, defaults in `CheckoutProperties`): hold time `reservation-ttl` 15 minutes; at most 3 unpaid orders per customer (`max-pending-orders`); at most 10 of one product reserved across a customer's unpaid orders (`max-reserved-per-product`); idempotency keys live 24 hours (`idempotency-ttl`); the expiry job runs every 30 s in batches of 50 and an hourly job deletes expired keys. Conflicts are 409 problems with an `errors` array: `cart-changed`, `price-changed`, `out-of-stock`, `cart-full`, `too-many-pending-orders`, `reservation-limit`, `order-state`, `idempotency-in-progress`; a reused key with another body is 422 `idempotency-key-reused`. Customer request bodies on `/api/**` are capped at 16 KiB (413 `body-too-large`), and a body that is not `application/json` gets 415 `unsupported-media-type` before it is read; the cap applies to routes that read a body, so a body sent to cancel or `DELETE` of a cart line is discarded by Tomcat rather than refused. Bad parameter types give a fixed 400 message that names only the parameter. Design and lock order: `docs/adr/0008-checkout-and-stock-reservation.md`.

### Payment and confirmation email

`Pay now` on an order in `Awaiting payment` calls `POST /api/orders/{id}/payment` and goes to the provider's page. The only provider is a fake that lives inside the API (`shop.payment.provider=fake`, set in compose and in the local overlay; startup fails when it is unset; the API logs a warning that no money is taken). Its page is the web route `/fake-psp/<token>`: it shows the amount, the merchant reference and when the session ends, with `Pay` and `Decline`. `Pay` makes the fake send a signed webhook to the API's own `POST /api/payments/webhook` (header `Shop-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 over t.body>`, 300 s window, secret `SHOP_PAYMENT_WEBHOOK_SECRET`). The order page then shows `Payment is being confirmed.` for up to 20 s and turns to `Paid`. `Decline` leaves the order awaiting payment so the customer can try again. If the session has expired or the payment is already completed, the page says which (it refetches after a 409) and links back to the order. A payment that arrives after the hold ended is still taken when the stock is free and the order is within `shop.checkout.late-payment-window` (30 minutes; the order shows a late note); otherwise the order shows `Refund pending` and then `Refunded`. A second payment for a paid order and a payment with the wrong amount are refunded and leave the order unchanged; refunds go to the fake provider. A paid order triggers one confirmation email, sent by a listener on the Spring Modulith event registry; a failed send is retried by `EventRetryJob` with a growing backoff (1 minute up to 30). Mailpit shows the mail: http://localhost:8025 in `make dev`, http://localhost:8026 in kind, internal only in the e2e stack. Design, state table and failure handling: `docs/adr/0009-payments-webhook-and-outbox.md`.

Failed events that stay failed for 48 attempts (about a day) move to `event_publication_dead_letter`. Resubmit one by hand with `psql` in the `shop` database (dev: `docker compose` Postgres on `localhost:5432`):

```sql
WITH back AS (DELETE FROM event_publication_dead_letter WHERE id = '<publication id>' RETURNING *)
INSERT INTO event_publication (id, listener_id, event_type, serialized_event, publication_date, completion_date,
                               status, completion_attempts, last_resubmission_date)
SELECT id, listener_id, event_type, serialized_event, publication_date, completion_date, status, 0, NULL FROM back;
```

`EventRetryJob` picks the row up on its next run (every 60 s).

### Admin writes locally

Sign in as `admin@example.test` at http://localhost:5173 (`make dev`) and open Admin. "New product" creates a product (unchecked `Active` keeps it hidden from the public catalog); a product's edit page changes its fields and, in a separate form, its stock. The Audit link lists every create, edit and stock change with the admin's display name and time (the API also returns the actor's `sub`; the table does not show it yet). Each form saves with the version it loaded, so two admins editing the same product get a conflict message instead of overwriting each other. Design and rules: `docs/adr/0007-admin-product-and-stock.md`.

### Spring profiles

| Profile | Effect |
|---|---|
| none (production defaults) | JSON logs, no demo data, `/v3/api-docs` off |
| `dev` | Readable console logs, `/v3/api-docs` on |
| `seed` | Loads the demo catalog (about 60 products, 6 categories) with stock quantities |

## Targets

Run `make help` for the list. `make ci` is exactly what the PR workflow runs: `lint test build scan e2e`.

| Target | Purpose |
|---|---|
| `dev`, `down`, `logs` | Local stack; `make dev OBS=1` adds Grafana and OTLP export |
| `api-stop`, `api-start` | Stop and start the API in the local stack, to see the unavailable state |
| `lint` | Spotless, ESLint, `tsc`, Prettier (API, web and `tests/e2e`), `k8s-lint`, checks that the generated API client and route tree are up to date, Playwright version lock, hadolint, actionlint, zizmor, `compose config` |
| `test` | `mvn verify` (unit and Testcontainers tests) and `vitest run --coverage`; both fail below 100% coverage |
| `build` | Images `shop/api:dev` and `shop/web:dev` |
| `e2e` | Playwright tests in Chromium against the built images behind a test-only gateway; reports in `build/reports/e2e`. Pass Playwright arguments with `E2E_ARGS`, e.g. `make e2e E2E_ARGS=specs/catalog.spec.ts` |
| `cluster-up`, `cluster-down` | Create the kind cluster `shop` with its add-ons (idempotent), and remove it with its kubeconfig |
| `deploy` | Build, load `shop/api:dev` and `shop/web:dev` into the cluster and apply `deploy/k8s/overlays/local`; `OVERLAY=obs` also exports telemetry |
| `cluster-e2e` | The Playwright suite against Envoy in the cluster, plus the NetworkPolicy test, the PDB eviction check and an HPA replica report; reports in `build/reports/cluster-e2e`. `E2E_ARGS` works as for `e2e` |
| `cluster-smoke`, `cluster-diagnostics`, `cluster-traffic` | Check `127.0.0.1:8088`, collect pod state and logs, and send steady traffic (used to watch a rollout) |
| `obs-up`, `obs-down` | Install or remove the observability stack in kind (needs `cluster-up`); Grafana at http://grafana.localhost:8088 |
| `obs-check`, `obs-check-dev` | Fail when a metric the dashboard queries is missing in the kind Prometheus (after `make deploy OVERLAY=obs`) or in the compose `otel-lgtm` (after `make dev OBS=1`) |
| `obs-memory` | Fail when the memory requests in `obs` exceed 1 GiB (run by `obs-up`) |
| `kubectl` | Run kubectl in the tools container: `make kubectl ARGS="-n shop get pods"` |
| `k8s-lint`, `k8s-tools` | Render and validate the manifests with kubeconform (part of `lint`); build the kind, Helm and kubectl tools image |
| `generate` | Regenerate everything derived from code, in order: `api-spec`, `web-api-client`, then the route tree. Run it after changing the API contract or routes, and commit the results |
| `api-spec` | Rewrite `services/api/openapi/openapi.json` by running only `OpenApiContractIT` (needs the Docker socket like `make test`) |
| `web-api-client` | Regenerate `apps/web/src/api/schema.d.ts` from the committed OpenAPI document |
| `scan` | Trivy on the repo and both images; fails on fixable HIGH/CRITICAL findings |
| `fmt` | Apply Spotless and Prettier (API, web, `tests/e2e`) |
| `clean` | Remove stack volumes and build outputs (keeps `.cache`) |
| `renovate-validate` | Validate `renovate.json` (large image, not part of `ci`) |

Scan reports (JSON and SARIF) are written to `build/reports/trivy/`, end-to-end reports to `build/reports/e2e/`, coverage to `services/api/target/site/jacoco/` and `apps/web/coverage/`.

## Layout

```
Makefile                    entry point
deploy/versions.mk          every image and tool version, updated by Renovate
deploy/compose/compose.yaml local dev stack
deploy/keycloak/            Keycloak image and the realm definition (realm as code)
services/api/               Spring Boot API
services/api/openapi/       committed OpenAPI document (generated, see make generate)
apps/web/                   React app
deploy/k8s/                 Kustomize base and local overlay for the app
deploy/k8s/overlays/local/  also Mailpit (`mailpit.yaml`) and its NetworkPolicies (`network-policies-mail.yaml`); never in `base`
deploy/kind/                kind cluster config, Calico, Helm values (incl. metrics-server), GatewayClass
deploy/observability/       Helm values for the obs stack, the Grafana route, the dashboard (`dashboards/shop-api.json`, shared by both stacks) and `check-metrics.sh`
deploy/k8s/components/otel/ OTLP export environment and its NetworkPolicy; `overlays/obs` applies it
make/obs.mk                 observability targets
deploy/k8s-tools/           Dockerfile for the kind, Helm and kubectl tools image
tests/e2e/                  Playwright project and its Compose stack
tests/e2e/cluster/          NetworkPolicy test and its probe pods
tests/manual/               checklist of checks that cannot be automated
tests/README.md             map of all test kinds
docs/adr/                   architecture decisions
docs/plans/                 plan, per-slice briefs and status
.cache/                     gitignored Maven, pnpm, Trivy and HOME caches
build/                      gitignored reports
```

## Where things are

- `docs/plans/`: the master plan, the brief for each slice and `STATUS.md`. Start with `docs/plans/README.md` to see where the work stands and how to resume it.
- `tests/`: every kind of test, what it proves and how to run it, how the end-to-end stack works and how to debug it (`tests/README.md`). Unit and integration tests live next to the code in each app.
- `docs/adr/`: why the tooling, testing, catalog API, web security headers, local Kubernetes cluster, authentication, admin writes, checkout with stock reservation, payment with the webhook, outbox and registration, and observability are set up this way.
- `docs/threat-model.md`: assets, trust boundaries, threats and residual risks of sign-in, sessions, admin writes, checkout, payment with the webhook, and the observability stack.

## Manual steps

Some things cannot be automated: first-time Docker group setup, checking the first run on a GitHub runner, Renovate and branch protection settings, screen-reader and non-Chromium checks. They are listed with exact steps in `tests/manual/README.md`.

## Known limits

- Kubernetes (kind): on a cgroup v1 Docker Desktop (this laptop's) the cluster sets `failCgroupV1: false` for kubelet 1.36, which is a stopgap; CI is cgroup v2. The HPA owns the API's `replicas`, so returning to a pre-5a branch needs `make cluster-down cluster-up deploy`. `make cluster-up` reapplies a Helm release only when its chart version or values change. No TLS or HSTS on localhost; one node and one Postgres instance, with data lost on `make cluster-down`; `make deploy` uses local `:dev` images (registry and digests come in slice 6); after a Docker Desktop restart the cluster may not recover, so run `make cluster-down cluster-up deploy`; port 8088 must be free; `make cluster-e2e` reaches the cluster through the node hostname with a browser host mapping, not through your host's localhost.
- GitHub code scanning (SARIF upload and the CodeQL workflow) needs a public repository or GitHub Advanced Security. The workflows check `github.event.repository.visibility == 'public'`: SARIF uploads and CodeQL are skipped on private repositories, and SARIF uploads are also skipped on fork pull requests.
- CodeQL may warn about TypeScript 7 support.
- Two TypeScript versions are installed in `apps/web` and in `tests/e2e`. `typescript-eslint` needs the TypeScript 6 JS API, so the `typescript` package is aliased to `@typescript/typescript6`; `tsc` (type check and build) comes from the `typescript7` alias, which is TypeScript 7. Drop the alias once `typescript-eslint` supports TypeScript 7.
- The web and API runtime images run `apk upgrade` because their pinned base images (nginx, Temurin JRE) lagged Alpine security fixes. Remove each when Renovate brings a base image that passes the scan without it.
- Catalog search matches whole words only: no prefixes and no typo tolerance (`docs/adr/0003-catalog-data-and-api.md`).
- Products have no images yet; the web app shows a placeholder.
- The Vite dev server does not enforce the Content Security Policy; only the built web image does (`docs/adr/0004-web-security-headers.md`).
- Payment (slice 4b) uses a fake provider inside the API: no money moves, the fake signs with the same key the API verifies with (`docs/threat-model.md`, R47), and rotating the webhook secret needs a deploy (R39). Mailpit is local only (never in `base`) and unauthenticated: anyone who can reach it reads every mailbox, including verification links (R38). The first `make cluster-down cluster-up` after 4b wipes the kind database. Registered users in kind are lost when Keycloak restarts. A crash between sending the confirmation and recording it can send the mail twice. Two to four API replicas since slice 5a (R14). The checkout limits (3 unpaid orders, 10 of a product, 20 lines) are per account, there is no rate limit until slice 5c, and cancel-and-reorder churn grows the order tables without retention (`docs/threat-model.md`, R37). Hot stock rows can answer 503 under heavy contention (R43). `available` on a cart line lets a customer learn exact free stock when it is 10 or less (R44). The expiry job is the only bulk path that returns stock from overdue orders and has dashboard panels but no alert until slice 5c (R50); an overdue order keeps its stock for up to 30 s after it stops counting toward the caps. `orders.email` stores the verified email at checkout as personal data (R45). A customer disabled in Keycloak can keep using cart and checkout for up to about 5 minutes (R42).
- Spring Security now sets the API's security headers; HSTS waits for the TLS-terminating Gateway. Keycloak runs with `sslRequired: external`, so it accepts plain http only from localhost and private addresses; the cloud setup uses `all`.
- Cookies are not port-scoped: `__Host-SESSION` and `__Host-XSRF-TOKEN` from `localhost:8088` are also sent to Keycloak on `localhost:8089`, and the XSRF cookie is script-readable there. Separation needs distinct hostnames (`docs/threat-model.md`, R4).
- No rate limiting until slice 5c. Keycloak's brute-force protection sees only the gateway address, so anyone can lock out an account.
- Valkey keeps no data: a restart signs everyone out. Keycloak in kind re-creates the realm on every restart (new signing keys, SSO sessions lost); the Deployment uses `Recreate`. Compose Keycloak databases are tmpfs, so the realm is imported fresh on each start.
- With Valkey down, the public catalog keeps working but a request carrying a session cookie gets 503. With Keycloak down, sign-in fails and signed-in sessions continue until the 15-minute revalidation grace ends. Role changes and disabled users take effect within about 5 minutes. A refused revalidation ends the session and clears its CSRF token; a refresh without an ID token counts as Keycloak being unavailable.
- In kind, Valkey's image version is pinned only in `deploy/versions.mk`, and a secrets hash annotation restarts `api` and `keycloak` when `.cache/secrets/shop.env` changes.
- The client IP from `X-Forwarded-For` is not trustworthy yet and nothing uses it; fix it in slice 5c before rate limits use it (`docs/threat-model.md`, R20).
- Observability (slice 5a): the `obs` namespace has no NetworkPolicies and Grafana allows anonymous Viewer on loopback (R55, R56); a client-supplied `traceparent` sets its request's trace id (R57); export failures only drop telemetry (R58). Gateway body limits, `statement_timeout` and Alertmanager rules move to slice 5c; gateway limits, rate limits and the `X-Forwarded-For` fix are listed above. Load tests and the catalog cache are slice 5b.
- Keycloak's image scan fails only on fixable CRITICAL findings; HIGH is reported (gated in slice 6).
- Until 2026-12-02 `scan-web` ignores 6 HIGH nginx 1.30.6 CVEs (HTTP/2, HTTP/3, TLS, mail, proxy with SSI or mirror; none reachable with `apps/web/nginx.conf`) in `apps/web/.trivyignore.yaml`, pending the nginx-unprivileged 1.30.7 image.
- Playwright traces from failed e2e runs record typed test passwords (`tests/README.md`).
- Admin writes: the slug cannot change after create; a product is hidden by clearing `Active`, never deleted. Admins set `onHand`; `reserved` belongs to checkout (slice 4a). There is no audit filter or export and no rate limit on writes until slice 5c. The audit log is append-only by database triggers, but the database owner role can disable them (`docs/threat-model.md`, R21).
- A demoted admin can still write for up to 5 minutes while Keycloak is up. With Keycloak down, admin writes get 503 once the last success is older than 5 minutes, while reads continue to the 15-minute grace.
- On dev and kind, changing the seed file re-activates a seed product an admin deactivated and overwrites an admin-created product with the same slug. Stock rows an admin edited are kept.
- The first `make dev` is slow while Maven downloads dependencies into `.cache/m2`.
- `trivy convert` cannot filter unfixed vulnerabilities, so `--ignore-unfixed` is applied at scan time and also narrows the SARIF output.
- `make test` mounts the Docker socket into the Maven container, which is root-equivalent on the host. See `docs/adr/0001-containerised-tooling.md`. The kind container in `cluster-up`, `deploy` and `cluster-diagnostics` gets it too (`docs/adr/0005-local-kubernetes-on-kind.md`); kubectl and Helm do not.
