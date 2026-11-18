# Tests

This directory holds the tests that belong to no single app: the end-to-end suite (`e2e/`) and the checklist of checks that cannot be automated (`manual/`).

Unit and integration tests are not here. They stay next to the code they test, where Maven (`services/api/src/test/java`) and Vitest (`*.test.ts(x)` beside each source file) expect them. Moving them would mean fighting both tools.

## Test kinds

| Kind | Location | Tool | What it proves | Command |
|---|---|---|---|---|
| API unit (79) | `services/api/src/test/java`, classes named `*Test` | JUnit, Mockito, Spring slice tests (Surefire) | Controllers and `main` behave correctly in isolation, without a database; Spring Modulith module boundaries hold (`ModularityTest`); the auth building blocks (session allowlist deserialiser, safe session repository, redirect handling, exception handler mapping of security errors) | `make api-test` |
| API integration (153) | `services/api/src/test/java`, classes named `*IT` | Spring Boot test, Testcontainers Postgres and Valkey, a stub identity provider (Failsafe) | The real application starts against a real Postgres and Valkey, Flyway migrates, catalog queries (search, ranking, filter, sorts, paging, hidden inactive products) and the HTTP contract, the committed OpenAPI document matches the code (`OpenApiContractIT`), request logs carry the trace id from `traceparent`, `/api/status` answers, only health is exposed on the management port, readiness turns 503 when the database stops while liveness stays 200. Auth: OIDC login (PKCE, state, nonce, exact redirect URI, session id rotation, cookie attributes, role mapping, every bad-token case), both logouts, back-channel logout by `sid` and `sub` with every invalid token leaving the session alive, absolute and idle timeouts, revalidation (grace, fail closed, single flight), CSRF, Valkey outage (public GET 200, cookie request 503), deny-by-default for every endpoint as anonymous, customer and admin, spoofed forwarded headers. 100% line and branch | `make api-test` |
| Web unit and component | `apps/web/src`, files named `*.test.ts(x)` | Vitest, Testing Library, jsdom | Catalog rendering, search, category filter, sort, pagination and URL sync, search-param fallbacks, loading, empty and error states with retry, product page and not found, price formatting, and the status view; sign-in and sign-out, header states, `returnTo` handling, the admin page for each role, CSRF header handling (150 tests, 100%) | `make web-test` |
| End-to-end (78) | `tests/e2e/specs` | Playwright (Chromium), axe-core | The built images work together with a real Keycloak in a real browser: the catalog flow (search, filter, sort, paging, deep links, product page, not found), `/status` shows API status and recovers after a simulated outage, the Content Security Policy has no violations and the security headers are present, no WCAG A/AA axe violations on the main pages and on the signed-in header and admin page. Auth specs: `auth` (sign in and out as customer and admin, cookie attributes, session id changes at login, `returnTo`, failed login), `csrf` (403 without token, 401 and 403 on the admin API, sign-out with token), `keycloak` (issuer, admin console, master realm, client registration and management port not routed; PKCE, implicit, password grant, token exchange, device flow and self-registration refused), `logout` (expired `id_token_hint`, IdP-initiated back-channel logout), `admin` (read-only table for admin, no data for customer), `signed-in` (CSP and axe as customer and admin) | `make e2e` |
| Cluster end-to-end (77) | `tests/e2e/specs` | Playwright (Chromium) | The same specs, plus a routing spec, against Envoy Gateway in a kind cluster with Postgres from CloudNativePG, Keycloak and Valkey. The expired-`id_token_hint` spec skips here (checked by hand, see `manual/README.md`) | `make cluster-e2e` |
| NetworkPolicy | `tests/e2e/cluster` | `kubectl exec` from restricted probe pods | The default-deny policies enforce for the app, database, Keycloak and Valkey pods: 22 checks, 6 allowed and 16 denied (a denial counts only as a timeout) | `make cluster-e2e` |
| Manual | `tests/manual/README.md` | A person | Things that need a human, a GitHub runner or another machine | See the checklist |

`make test` runs both API kinds and the web tests. `make ci` runs `lint test build scan e2e`, which is what the pull request workflow runs. `make cluster-e2e` runs in its own `cluster-e2e` CI job.

## Coverage policy

- The API must have 100% line and branch coverage, the web app 100% of lines, branches, functions and statements.
- Coverage is measured over unit and integration tests together and enforced by the build: JaCoCo `check` fails `mvn verify`, Vitest `thresholds` fail `vitest run --coverage`. A drop below 100% fails `make test`.
- Exactly two exclusions exist, both in the web app, approved by the owner on 2026-11-17: the machine-generated `src/routeTree.gen.ts` and the unmodified vendored shadcn primitives in `src/components/ui/`. Any other exclusion needs the owner's approval. If a primitive is edited it moves under the gate (`docs/adr/0002-testing-strategy.md`). The API has none.
- All other production code is measured. `ApiApplication.main` is covered by a unit test that mocks `SpringApplication.run` statically; `main.tsx` is covered by tests that render into `#root`.
- End-to-end tests are not measured.

Reports:

| Report | Location |
|---|---|
| API coverage (HTML and XML) | `services/api/target/site/jacoco/` |
| Web coverage (HTML; a text summary is printed by the test run) | `apps/web/coverage/` |
| End-to-end HTML report | `build/reports/e2e/html/` |
| End-to-end traces and screenshots | `build/reports/e2e/artifacts/` |
| End-to-end stack logs | `build/reports/e2e/stack.log` |
| Cluster end-to-end (HTML, artifacts, `network-policy.log`, `diagnostics/`) | `build/reports/cluster-e2e/` |
| Trivy scans | `build/reports/trivy/` |

All of these are gitignored.

## How the end-to-end stack works

`make e2e` first builds the runtime images `shop/api:dev` and `shop/web:dev`, because those are the artefacts that ship, then installs the e2e dependencies. It then starts a separate Compose project, `shop-e2e` (`tests/e2e/compose.yaml`), with:

- `postgres`: Postgres with its data on tmpfs, thrown away after each run.
- `valkey`: the session store, with a password and no persistence.
- `keycloak-db` and `keycloak`: Keycloak with its own tmpfs Postgres, so the realm in `deploy/keycloak/shop-realm.json` is imported fresh every run. The e2e realm uses a 10 second access-token lifespan (`SHOP_ACCESS_TOKEN_LIFESPAN`, passed to the runner as `E2E_ACCESS_TOKEN_LIFESPAN`) so the expired-`id_token_hint` spec can wait it out. Test users and passwords come from `.cache/secrets/shop.env` (`make secrets`); the runner receives only the two test-user passwords.
- `api` and `web`: the built images. The API runs with the `seed` profile so the catalog has data.
- `gateway`: a plain nginx using `tests/e2e/gateway.conf`. It sends `/api`, `/oauth2/`, `/login/oauth2/` and exact `/logout` to the API and everything else to the web container. A second server on port 8089 stands in for the Keycloak origin and routes only `/realms/shop/` and `/resources/`; the specs reach it as `http://localhost:8089` through the `idpRequest` fixture (`E2E_IDP_UPSTREAM`). Access logs carry paths only, never query strings (authorization codes, `id_token_hint`); nginx's error log may still print a URL with a query in a buffering warning. The web image deliberately does not proxy `/api`; in the cluster, Envoy Gateway does that. This test gateway only stands in for it and is used nowhere else.
- `e2e`: the Playwright runner, in the Playwright image from `deploy/versions.mk`. The browser opens `http://localhost:8088`, a secure context, and Chromium maps that host to `gateway:8080` (`E2E_UPSTREAM`, see `playwright.config.ts`). The `request` fixture in `specs/support/test.ts` connects to the upstream directly with `Host: localhost:8088`, so specs import `test` from there when they use `request`. Nothing is published to the host.

Every service except the runner has a healthcheck, and the runner starts only after `up --wait` reports all of them healthy. The stack is always taken down with its volumes afterwards, and the test exit code is kept.

Dependencies and lint for `tests/e2e` run in the same Node tools image as the web app, so `make lint` never pulls the large Playwright image. The `@playwright/test` version in `tests/e2e/package.json` must equal the Playwright image tag in `deploy/versions.mk`; `make lint` fails if they differ, and Renovate updates both in one pull request.

The API outage test is simulated in the browser: `page.route` aborts requests to `/api/status`, then the route is removed and the page must recover within 15 seconds without a reload. It does not stop the API container.

## How the cluster e2e works

`make cluster-e2e` runs against the cluster from `make cluster-up deploy`. On an existing cluster run `make cluster-down cluster-up` once so the node maps port 8089. It first checks the front door `127.0.0.1:8088`, then runs the Playwright image on the `kind` Docker network with `E2E_UPSTREAM=shop-control-plane:30080`, the NodePort behind Envoy. The browser still sees `http://localhost:8088` through the same host mapping as in the Compose stack, so both stacks run the same specs unchanged. `specs/routing.spec.ts` runs in both and proves the API management port is not exposed: `/actuator/health` returns the web app, and `/api/actuator/health` and other actuator paths are denied (401, since unknown `/api` paths are deny-by-default). Keycloak is reached through the `idp` listener on 8089 (`E2E_IDP_UPSTREAM=shop-control-plane:30089`). The compose realm's 10 second lifespan is not applied in kind, where the realm default of 300 seconds holds, so the C23 spec skips there.

The NetworkPolicy test (`tests/e2e/cluster/network-policy-test.sh`) starts probe pods from the Postgres image, with the labels of `api`, `web` and an unrelated app in `shop`, and one pod in a separate namespace `netpol-probe`. For each connection it resolves the name first, then tries to open a TCP socket with a 5 second timeout. Allowed connections must succeed; denied ones must time out (exit 124, because Calico drops packets). Any other result fails the test. The probes are deleted when the script ends.

## Debugging a failing e2e run

1. Open `build/reports/e2e/html/index.html` in a browser. It lists each test with its steps and, for failures, a screenshot and a trace.
2. Traces are the `trace.zip` files under `build/reports/e2e/artifacts/`. The HTML report may not open them from a `file://` page; drop the zip onto https://trace.playwright.dev instead (it is processed in the browser, nothing is uploaded). The trace shows each action, the DOM at that moment, network calls and console output. Failure traces and the HTML report record what the test typed, including the test-user passwords from the sign-in form. They are generated per checkout and local, but do not share trace files or upload them outside CI artifacts (threat model R10).
3. Read `build/reports/e2e/stack.log` for the logs of Postgres, Valkey, Keycloak, API, web and gateway. It is written after every run, passing or failing. A failure at startup (`up --wait` timed out) shows up here too.
4. In CI, the same folder is uploaded as the `e2e-reports` artifact when the job fails and kept for 7 days.

For the cluster, look in `build/reports/cluster-e2e/` instead: the Playwright HTML report and artifacts, `network-policy.log` (one `PASS` or `FAIL` line per connection) and `diagnostics/` (pod state, events and logs, no credentials). `make cluster-diagnostics` collects the diagnostics on demand. If the NetworkPolicy test passes in CI but fails locally, check the `calico-node` logs first.

To run part of the suite, pass Playwright arguments through `E2E_ARGS`: `make e2e E2E_ARGS=specs/catalog.spec.ts` for one file, or `make e2e E2E_ARGS="-g outage"` for tests whose title matches.

## Manual checks

Some checks cannot be automated, for example the first run on a GitHub runner or a screen-reader pass. They are in [manual/README.md](manual/README.md).
