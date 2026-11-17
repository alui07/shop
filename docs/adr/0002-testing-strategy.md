# ADR 0002: Testing strategy

Status: accepted, 2026-11-16

## Context

Slice 0 delivered the toolchain. Before features arrive we want a test setup that stays honest: tests that are cheap to run, a coverage gate nobody can quietly lower, and a browser test of what actually ships. The rules in ADR 0001 still apply (containers only, everything through `make`).

## Decisions

**Test kinds and where they live.** Unit and integration tests stay next to the code, as Maven (`src/test/java`, `*Test` for Surefire, `*IT` for Failsafe) and Vitest (`*.test.ts(x)` beside the source) expect. `tests/` holds only what belongs to no single app: `tests/e2e/` and `tests/manual/`. `tests/README.md` maps all kinds.

**Coverage is 100% and enforced.** The API needs 100% line and branch coverage and the web app 100% of lines, branches, functions and statements, measured over unit and integration tests together. JaCoCo `check` (in `verify`, after Failsafe) and Vitest `thresholds` fail the build. Production code is never excluded. `ApiApplication.main` is covered by a unit test that mocks `SpringApplication.run` with Mockito's static mocking, rather than being excluded or starting a context. End-to-end tests are not measured. 100% is a floor that catches untested branches, not a claim that the tests are good; review still matters.

**End-to-end tests run against the built images.** `make e2e` builds `shop/api:dev` and `shop/web:dev` and starts them in a separate Compose project with Postgres on tmpfs, so the tests exercise what ships rather than the dev servers. The web image does not proxy `/api`; the Gateway of slice 2 will. A test-only nginx container routes `/api/` to the API and everything else to the web container, standing in for that Gateway. It is used nowhere else and should be replaced or reconfigured when the real Gateway exists. Playwright runs from the Playwright image pinned in `deploy/versions.mk`; dependencies, lint and formatting for `tests/e2e` use the existing Node tools image so `make lint` never pulls browsers. The `@playwright/test` version must equal the image tag, which `make lint` checks and Renovate updates in one group.

**The API outage is simulated in the browser.** The test aborts `/api/status` requests with `page.route` and removes the route to check recovery. It proves the front end handles failure and retries; it does not prove behaviour when the real API container stops. The integration test `ReadinessIT` covers the real database outage on the API side.

**Chromium only.** One browser keeps the suite fast and the image small. Cross-browser behaviour is a recurring manual check (`tests/manual/README.md`).

**Reports.** Coverage, the Playwright HTML report, traces and stack logs are written under gitignored folders (`build/reports/e2e`, `services/api/target/site/jacoco`, `apps/web/coverage`) and skipped by Trivy, Prettier and ESLint. CI uploads `build/reports/e2e` as an artifact when the job fails.

## Consequences

`make ci` is slower: it now builds images before the browser tests and pulls the Playwright image. Contributors cannot merge code without tests for every branch. Out of scope for now: load tests, visual regression, mutation testing, generated API client contract tests (slice 1) and Kubernetes-level tests (slice 2).
