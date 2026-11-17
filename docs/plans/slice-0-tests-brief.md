# Slice 0 follow-up brief: test suite, coverage gates, end-to-end tests, test docs

Repo: `/home/alui/code/shop`. Slice 0 is implemented and reviewed but not yet committed. Read `docs/plans/slice-0-brief.md` (its amendments are in force) and `docs/adr/0001-containerised-tooling.md` first; all of their rules still apply (containers only, host UID, bind-mounted `.cache`, pinned images in `deploy/versions.mk`, socket only for `mvn verify`, no git index changes, no comments except non-obvious workarounds, files under 500 lines).

## Owner's request

Unit tests pushed to 100% coverage, integration tests, end-to-end tests, a test directory with a README, write-ups for anything that must be done manually, and an explanation of what the plan and test directories are for.

## Goal

- `make test` runs unit and integration tests for both apps and fails if coverage is below 100%.
- `make e2e` runs browser tests against the built runtime images.
- `make ci` = `lint test build scan e2e`, and the PR workflow still only calls `make ci`.
- `tests/README.md`, `tests/manual/`, `docs/plans/README.md` and the root README explain where everything lives and what is manual.

## Versions (read from registries 2026-11-16; npm versions respect the 7-day release-age gate)

| Thing | Version |
|---|---|
| JaCoCo Maven plugin | 0.8.16 (confirm it supports Java 25 class files; if not, report) |
| `@vitest/coverage-v8` | 5.1.0 (must equal the installed `vitest` version) |
| `@playwright/test` | 1.65.0 (1.65.1 is younger than 7 days) |
| Playwright image | `mcr.microsoft.com/playwright:v1.65.0-noble`, digest-pinned; must match the npm version exactly |
| `@axe-core/playwright` | newest release at least 7 days old |
| `actions/upload-artifact` | v7.0.2 `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |

## Design decisions

1. **Where tests live.** Unit and integration tests stay next to the code they test, as Maven (`src/test/java`, `*Test` and `*IT`) and Vitest (`*.test.ts(x)` beside the source) expect; moving them would fight both toolchains. The new top-level `tests/` directory holds what belongs to no single app: `tests/e2e/` (Playwright project and its stack) and `tests/manual/` (checks that cannot be automated). `tests/README.md` is the map of all test kinds.
2. **Coverage definition.** 100% line and branch for the API, 100% lines, branches, functions and statements for the web app, measured over unit and integration tests together, enforced by the build (JaCoCo `check`, Vitest `thresholds`). No exclusions of production code. End-to-end tests are not measured.
3. **API coverage mechanics.** JaCoCo `prepare-agent` (Surefire) and `prepare-agent-integration` (Failsafe), `merge` into one exec file, then `report` and `check` on the merged data in the `verify` phase, after Failsafe's `verify`. Rule: BUNDLE, LINE and BRANCH covered ratio 1.00. HTML and XML report in `services/api/target/site/jacoco/`. `mvn spotless:check` and `spring-boot:run` are unaffected.
4. **`ApiApplication.main`** is covered by a unit test that uses Mockito `mockStatic(SpringApplication.class)` and verifies `run(ApiApplication.class, args)`. This keeps "no exclusions" honest without starting a context. If static mocking needs agent configuration on Java 25 / Mockito 5 as shipped by Boot 4.1, configure it the way the Mockito docs say; do not suppress warnings blindly.
5. **Web coverage mechanics.** `@vitest/coverage-v8`, `coverage.include: ['src/**/*.{ts,tsx}']`, test files excluded, `thresholds: { lines: 100, branches: 100, functions: 100, statements: 100 }`, reporters `text` and `html` into `apps/web/coverage/`. `pnpm run test` becomes `vitest run --coverage`. `src/main.tsx` is tested (renders into `#root`; throws when `#root` is missing), not excluded.
6. **New API tests.**
   - Unit: `StatusController` returns the build version when `BuildProperties` is present (plain unit test with a real `BuildProperties` and a stub `ObjectProvider`, or a slice test with a bean; implementer's choice), alongside the existing "unknown" case. `ApiApplicationTest` per decision 4.
   - Integration (`*IT`, Testcontainers): keep `ApplicationIT`. Add `ManagementEndpointsIT`: actuator paths are not served on the application port (404 on `/actuator/health`); on the management port only health is exposed (`/actuator/env` and `/actuator/info` are 404). Add `ReadinessIT` with its own Postgres container: readiness returns 200, then after the container is stopped readiness returns 503 while liveness stays 200. Keep Hikari timeouts short in that test via properties so it finishes in seconds, not half a minute. Unknown `/api/...` path returns 404.
   - Share the Postgres container definition (image from env `POSTGRES_IMAGE`, `asCompatibleSubstituteFor`) in one test support class instead of copying it.
7. **New web tests.** `src/api/status.test.ts`: success, non-2xx, and each branch of the payload guard (not an object, null, missing or non-string `status`, missing or non-string `version`), signal passed through and defaulting to `null`. `src/App.test.tsx`: add the initial loading state and unmount during a pending request (no state update, timer cleared). `src/main.test.tsx` per decision 5. Assertions use explicit matchers, not `toBeTruthy()` on elements where a clearer assertion exists.
8. **End-to-end stack.** Tests run against the built runtime images (`shop/api:dev`, `shop/web:dev`), not the dev servers, because those images are what ships. The web image deliberately does not proxy `/api` (the Gateway does that from slice 2), so the e2e stack adds a small nginx (`NGINX_IMAGE`, mounted config) that routes `/api/` to the API and everything else to the web container: a stand-in for the Gateway, used only in tests. Compose project `shop-e2e`, separate from the dev stack, no published ports, Postgres data on tmpfs. The Playwright container joins the compose network and uses `E2E_BASE_URL=http://gateway:8080`.
9. **End-to-end scenarios (Chromium only).**
   - Home page shows status `ok` and the same version that `GET /api/status` returns.
   - Deep link (`/some/client/route`) serves the app (SPA fallback through the gateway).
   - API outage: with `/api/status` requests aborted via `page.route`, the page shows "API unavailable"; after `unroute`, it recovers without reload. The test file says plainly that the outage is simulated in the browser.
   - Accessibility: `@axe-core/playwright` finds no violations on the home page.
   - `/api/status` responds with `application/json` through the gateway (request-level check).
10. **Reports.** Playwright HTML report and failure traces go to `build/reports/e2e/`; CI uploads that folder as an artifact when the job fails. Coverage reports stay in each app's build output. All are gitignored and skipped by Trivy, Prettier and ESLint.
11. **Manual checks** are written as a checklist in `tests/manual/README.md`, each with why it cannot be automated, exact steps and the expected result:
    - one-time: Docker group login, first `make dev` smoke (edit a source file, see hot reload), IDE setup note (no host JDK or Node, so the IDE needs its own or a dev container; optional);
    - GitHub side, after the first push: PR workflow green on a Linux runner (first real proof of the Testcontainers host override), SARIF results visible under code scanning once public, first weekly CodeQL schedule not skipped, Renovate app installed and its dashboard issue appears, branch protection requiring the `ci` check;
    - per release until automated: none yet.

## Layout to add

```
tests/
  README.md                 map of test kinds, how to run, coverage policy, reports, what is manual
  manual/README.md          manual checklist
  e2e/
    package.json  pnpm-lock.yaml  pnpm-workspace.yaml  tsconfig.json  playwright.config.ts
    eslint.config.js  .prettierrc.json  .prettierignore  .dockerignore
    Dockerfile              FROM ${PLAYWRIGHT_IMAGE}; installs pnpm ${PNPM_VERSION}; workdir /e2e
    compose.yaml            postgres, api, web, gateway, e2e (runner)
    gateway.conf            nginx routing for the test gateway
    specs/*.spec.ts
docs/plans/README.md        what the plans directory is, file roles, how a new session resumes
```

## Fixed interface between agents

- `tests/e2e/compose.yaml` reads only these variables, all `${VAR:?set by make}`: `POSTGRES_IMAGE`, `NGINX_IMAGE`, `API_IMAGE` (`shop/api:dev`), `WEB_IMAGE` (`shop/web:dev`), `E2E_TOOLS_IMAGE` (`shop/e2e-tools:dev`), `HOST_UID`, `HOST_GID`. Paths in it are relative to the file. Services `postgres`, `api`, `web`, `gateway` have healthchecks so `up --wait` works (API: readiness on 8081 using a client that exists in the JRE Alpine image; verify). Service `e2e` is in profile `runner`, runs as `${HOST_UID}:${HOST_GID}` with `HOME=/cache/home`, mounts `tests/e2e` at `/e2e`, `../../.cache/pnpm` at `/cache/pnpm`, `../../.cache/home` at `/cache/home`, `../../build/reports/e2e` at `/reports`, env `E2E_BASE_URL=http://gateway:8080`, `pnpm_config_store_dir=/cache/pnpm`, command `pnpm run test`.
- `tests/e2e/package.json` scripts: `test` (`playwright test`), `lint`, `typecheck`, `format`, `format:check`. Playwright config writes the HTML report to `/reports/html` and traces/output to `/reports/artifacts` when `/reports` is the mount (env `E2E_REPORT_DIR`, default `./reports`), `reporter` list + html (never opens a browser), `retries: 0`, `forbidOnly` when `CI` is set.
- `tests/e2e/Dockerfile`: build args `PLAYWRIGHT_IMAGE`, `PNPM_VERSION`, no defaults; context `tests/e2e`; usable as an arbitrary UID.
- Makefile (platform agent) provides: `e2e-tools` (build image), `e2e-deps` (`pnpm install --frozen-lockfile` in the tools image, same run shape as the web tools), `e2e` (needs `build`; `compose up -d --wait`, `compose --profile runner run --rm e2e`, always `down --volumes` while preserving the test exit code), e2e lint/typecheck/format wired into `lint` and `fmt`, hadolint on the new Dockerfile, `compose config -q` on the new compose file, `clean` covering the new outputs.
- API: `mvn -B -ntp verify` is still the single test command and now also enforces coverage. Web: `pnpm run test` still the single command.

## Work split (parallel, no overlapping files)

- **Agent API**: `services/api/` only.
- **Agent WEB**: `apps/web/` only.
- **Agent E2E**: `tests/e2e/` only. Verifies with direct `docker` / `docker compose` commands, exporting the variables by hand from `deploy/versions.mk`; builds `shop/api:dev` and `shop/web:dev` through `make build` if they are missing.
- **Agent PLATFORM**: `Makefile`, `deploy/versions.mk`, `.github/workflows/ci.yaml`, `renovate.json` (group `@playwright/test` with the Playwright image so they move together; the regex manager must cover the new versions.mk entries), `.gitignore`, root `README.md`, `tests/README.md`, `tests/manual/README.md`, `docs/plans/README.md`, ADR update if a decision changes.

Each agent runs the real commands in containers and reports exact commands, results, and anything unverified. The orchestrator then runs `make ci` and both reviews.

## Out of scope

Load tests, cross-browser runs, visual regression, mutation testing, contract tests against a generated OpenAPI client (slice 1), Kubernetes-level tests (slice 2).

---

# Amendments after the design challenge (these OVERRIDE anything above that conflicts)

## End-to-end runner: no custom image
- T1. There is no `tests/e2e/Dockerfile` and no `E2E_TOOLS_IMAGE`. Dependencies for `tests/e2e` are installed, linted, type-checked and formatted with the existing `shop/web-tools:dev` image (same run shape as the web app, `tests/e2e` mounted at `/app`); nothing there needs browsers, so `make lint` never pulls the Playwright image. The runner service uses `${PLAYWRIGHT_IMAGE:?set by make}` directly with command `./node_modules/.bin/playwright test` (no pnpm inside it). The compose variable list is therefore: `POSTGRES_IMAGE`, `NGINX_IMAGE`, `API_IMAGE`, `WEB_IMAGE`, `PLAYWRIGHT_IMAGE`, `HOST_UID`, `HOST_GID`. If a dependency turns out to need the Node version it was installed with, report it instead of working around it.
- T2. Runner service: profile `runner`, `user: ${HOST_UID}:${HOST_GID}`, `init: true`, `shm_size: "1gb"` (not `ipc: host`), `working_dir: /e2e`, mounts `tests/e2e` at `/e2e`, `../../.cache/home` at `/cache/home`, `../../build/reports/e2e` at `/reports`; environment `HOME=/cache/home`, `CI=true`, `E2E_BASE_URL=http://gateway:8080`, `E2E_REPORT_DIR=/reports`. No pnpm store mount.
- T3. `api`, `web` use `pull_policy: never` (local-only images). Healthchecks use busybox `wget` (present in both runtime images): API on `http://localhost:8081/actuator/health/readiness`. Gateway config has `location = /healthz { return 200; }` for its healthcheck and proxies `/api/` with the path unchanged (`proxy_pass http://api:8080;`, no URI part).
- T4. Playwright config: `forbidOnly: true` unconditionally, `retries: 0`, `trace: 'retain-on-failure'`, `screenshot: 'only-on-failure'`, reporters `list` and `html` with `open: 'never'`, HTML to `${E2E_REPORT_DIR}/html`, output to `${E2E_REPORT_DIR}/artifacts` (default report dir `./reports`). Chromium only.
- T5. Outage scenario: register the route before `page.goto`; the recovery assertion has an explicit 15 s timeout because the app retries every 5 s. Run the axe check in both the healthy and the "API unavailable" states, limited to `withTags(['wcag2a','wcag2aa','wcag21aa','wcag22aa'])`.
- T6. Ignore lists owned by the E2E agent: `tests/e2e` ESLint ignores and `.prettierignore` cover `node_modules`, `reports`, `pnpm-lock.yaml`. No `.dockerignore` (nothing is built from this directory). `tests/e2e/pnpm-workspace.yaml` sets `minimumReleaseAge: 10080` and `packageImportMethod: copy`. TypeScript follows the same alias arrangement as `apps/web` only if ESLint needs it; otherwise plain TypeScript at a version that passes the release-age gate.

## Makefile and CI (platform agent)
- T7. `cache-dirs` also creates `build/reports/e2e`. `make e2e` depends on `build` and `e2e-deps`. Run, status capture and teardown are ONE recipe line so teardown always happens and the exit code survives, and the stack logs are saved:
  `$(E2E_COMPOSE) up -d --wait --wait-timeout 120 && { $(E2E_COMPOSE) --profile runner run --rm -T e2e; s=$$?; } || s=$$?; $(E2E_COMPOSE) logs --no-color > build/reports/e2e/stack.log 2>&1; $(E2E_COMPOSE) --profile runner down --volumes --remove-orphans; exit $$s` (adapt as needed; verify both the pass and the fail path, including a deliberately failing spec and a failed `up --wait`).
- T8. `.gitignore`: `apps/web/coverage/`, `tests/e2e/node_modules/`, `tests/e2e/reports/`. Trivy `scan-fs` also skips `**/coverage` and `tests/e2e/reports`. `clean` removes the new outputs. `ci.yaml`: add `tests/e2e/pnpm-lock.yaml` to the deps cache key; upload `build/reports/e2e` with `actions/upload-artifact` (SHA in the table) under `if: failure()` with a short retention.
- T9. Playwright version lock: `make lint` includes a guard that fails when the tag of `PLAYWRIGHT_IMAGE` in `deploy/versions.mk` (`vX.Y.Z-noble`) differs from the `@playwright/test` version in `tests/e2e/package.json`. Renovate: a package rule AFTER the non-major rule that groups `@playwright/test` and `mcr.microsoft.com/playwright` under their own `groupName`. Try a local dry run with the pinned Renovate image (`--platform=local --dry-run`) to see whether MCR tags carry a release timestamp; if they do not and the 7-day gate would hold the image forever, scope `minimumReleaseAgeBehaviour: "timestamp-optional"` to that package. If the dry run cannot be made to work offline, say so and add the check to the manual checklist instead.
- T10. Add `docs/adr/0002-testing-strategy.md` (one page): test kinds and where they live, the coverage policy (100% merged unit + integration, no exclusions, static mock for `main`), end-to-end against built images behind a test-only gateway standing in for the slice 2 Gateway, the simulated outage, Chromium only.
- T11. Manual checklist additions: keyboard and screen-reader pass (axe covers only part of WCAG; the `role="alert"` announcement is unverified); one non-Chromium browser smoke; first failing CI run uploads the e2e artifact; first Renovate Playwright PR moves package and image together; `make e2e` once on WSL/Docker Desktop as well as on CI.

## API (replaces decision 3 mechanics and refines 4 and 6)
- T12. JaCoCo: a single `prepare-agent` execution (default `target/jacoco.exec`, appended by both Surefire and Failsafe through the `argLine` property); no `prepare-agent-integration`, no `merge`. `report` then `check` bound to `verify`. The `jacoco-maven-plugin` is declared AFTER `maven-failsafe-plugin` in the POM so its `verify` goals run after Failsafe's, and `report` precedes `check` so the HTML exists when the check fails. Prove the gate works: temporarily break coverage, see `verify` fail, restore.
- T13. Mockito agent and JaCoCo both use `argLine`. Follow the Mockito docs (`maven-dependency-plugin:properties`), but write `<argLine>@{argLine} -javaagent:${org.mockito:mockito-core:jar}</argLine>` (late replacement) on Surefire and Failsafe, and declare an empty `<argLine/>` property so `@{argLine}` resolves when JaCoCo is skipped. Confirm in the build log that both agents are attached and the coverage report is not empty.
- T14. Integration test structure: one shared `@TestConfiguration(proxyBeanMethods = false)` exposing `@Bean @ServiceConnection PostgreSQLContainer` (image from env `POSTGRES_IMAGE`, `asCompatibleSubstituteFor("postgres")`, keep the explicit connection name if still required with digest refs). `ApplicationIT` and `ManagementEndpointsIT` import it with identical `@SpringBootTest` attributes so they share one context and one container. `ReadinessIT` has its own container, autowires it to call `stop()`, is annotated `@DirtiesContext`, sets `spring.datasource.hikari.connection-timeout=250` and `validation-timeout=250`, and polls with Awaitility (`atMost` 10 s) for readiness 503 while liveness stays 200.
- T15. "Unknown `/api/...` returns 404" belongs in `ManagementEndpointsIT` or `ApplicationIT` (healthy context). Also assert what `/actuator` (the discovery page) returns on the management port; if it lists links, say so in the report rather than changing production config silently.

## Web (refines decisions 5 and 7)
- T16. `coverage` is added to `apps/web` ESLint `globalIgnores`, `.prettierignore` and `.dockerignore` (the HTML reporter writes JS files that would otherwise be linted). Any test setup file lives outside `src/` or is excluded from coverage.
- T17. The branch `if (controller.signal.aborted) return;` is only reached when the fetch mock rejects on abort. Two separate scenarios: (a) unmount while the request is pending, with a mock that listens to `signal` and rejects with an `AbortError`; (b) unmount in the unavailable state with a retry pending (fake timers), asserting fetch is not called again. `main.tsx` tests live in their own file, using `vi.resetModules()` and a dynamic import per case with `fetch` stubbed.
