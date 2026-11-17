# Tests

This directory holds the tests that belong to no single app: the end-to-end suite (`e2e/`) and the checklist of checks that cannot be automated (`manual/`).

Unit and integration tests are not here. They stay next to the code they test, where Maven (`services/api/src/test/java`) and Vitest (`*.test.ts(x)` beside each source file) expect them. Moving them would mean fighting both tools.

## Test kinds

| Kind | Location | Tool | What it proves | Command |
|---|---|---|---|---|
| API unit | `services/api/src/test/java`, classes named `*Test` | JUnit, Mockito, Spring slice tests (Surefire) | Controllers and `main` behave correctly in isolation, without a database; Spring Modulith module boundaries hold (`ModularityTest`) | `make api-test` |
| API integration | `services/api/src/test/java`, classes named `*IT` | Spring Boot test, Testcontainers Postgres (Failsafe) | The real application starts against a real Postgres, Flyway migrates, catalog queries (search, ranking, filter, sorts, paging, hidden inactive products) and the HTTP contract, the committed OpenAPI document matches the code (`OpenApiContractIT`), request logs carry the trace id from `traceparent`, `/api/status` answers, only health is exposed on the management port, readiness turns 503 when the database stops while liveness stays 200 | `make api-test` |
| Web unit and component | `apps/web/src`, files named `*.test.ts(x)` | Vitest, Testing Library, jsdom | Catalog rendering, search, category filter, sort, pagination and URL sync, search-param fallbacks, loading, empty and error states with retry, product page and not found, price formatting, and the status view | `make web-test` |
| End-to-end | `tests/e2e/specs` | Playwright (Chromium), axe-core | The built images work together in a real browser: the catalog flow (search, filter, sort, paging, deep links, product page, not found), `/status` shows API status and recovers after a simulated outage, the Content Security Policy has no violations and the security headers are present, no WCAG A/AA axe violations on the main pages | `make e2e` |
| Manual | `tests/manual/README.md` | A person | Things that need a human, a GitHub runner or another machine | See the checklist |

`make test` runs both API kinds and the web tests. `make ci` runs `lint test build scan e2e`, which is what the pull request workflow runs.

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
| Trivy scans | `build/reports/trivy/` |

All of these are gitignored.

## How the end-to-end stack works

`make e2e` first builds the runtime images `shop/api:dev` and `shop/web:dev`, because those are the artefacts that ship, then installs the e2e dependencies. It then starts a separate Compose project, `shop-e2e` (`tests/e2e/compose.yaml`), with:

- `postgres`: Postgres with its data on tmpfs, thrown away after each run.
- `api` and `web`: the built images. The API runs with the `seed` profile so the catalog has data.
- `gateway`: a plain nginx using `tests/e2e/gateway.conf`. It sends `/api/` to the API and everything else to the web container. The web image deliberately does not proxy `/api`; in production the Gateway added in slice 2 will do that. This test gateway only stands in for it and is used nowhere else.
- `e2e`: the Playwright runner, in the Playwright image from `deploy/versions.mk`. It reaches the stack at `http://gateway:8080`. Nothing is published to the host.

Every service except the runner has a healthcheck, and the runner starts only after `up --wait` reports all of them healthy. The stack is always taken down with its volumes afterwards, and the test exit code is kept.

Dependencies and lint for `tests/e2e` run in the same Node tools image as the web app, so `make lint` never pulls the large Playwright image. The `@playwright/test` version in `tests/e2e/package.json` must equal the Playwright image tag in `deploy/versions.mk`; `make lint` fails if they differ, and Renovate updates both in one pull request.

The API outage test is simulated in the browser: `page.route` aborts requests to `/api/status`, then the route is removed and the page must recover within 15 seconds without a reload. It does not stop the API container.

## Debugging a failing e2e run

1. Open `build/reports/e2e/html/index.html` in a browser. It lists each test with its steps and, for failures, a screenshot and a trace.
2. Traces are the `trace.zip` files under `build/reports/e2e/artifacts/`. The HTML report may not open them from a `file://` page; drop the zip onto https://trace.playwright.dev instead (it is processed in the browser, nothing is uploaded). The trace shows each action, the DOM at that moment, network calls and console output.
3. Read `build/reports/e2e/stack.log` for the logs of Postgres, API, web and gateway. It is written after every run, passing or failing. A failure at startup (`up --wait` timed out) shows up here too.
4. In CI, the same folder is uploaded as the `e2e-reports` artifact when the job fails and kept for 7 days.

To run part of the suite, pass Playwright arguments through `E2E_ARGS`: `make e2e E2E_ARGS=specs/catalog.spec.ts` for one file, or `make e2e E2E_ARGS="-g outage"` for tests whose title matches.

## Manual checks

Some checks cannot be automated, for example the first run on a GitHub runner or a screen-reader pass. They are in [manual/README.md](manual/README.md).
