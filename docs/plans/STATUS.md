# Status and resume notes

This directory is the source of truth for plans. `master-plan.md` here supersedes the original at `~/.claude/plans/splendid-noodling-dewdrop.md`; update this copy, not that one.

- `master-plan.md`: the overall plan and build order (slices 0 to 6).
- `slice-N-brief.md`: the implementation brief for each slice, including amendments from the design challenge.
- `STATUS.md`: this file. Updated at every step change.

## Process per slice

1. Fable reads the code, checks versions against registries and docs, writes `slice-N-brief.md`.
2. An Opus agent challenges the brief; accepted findings are appended as amendments.
3. Sonnet agents implement in parallel on non-overlapping files.
4. Orchestrator runs the integrated checks (`make ci`, `make dev`).
5. Fresh Fable and Opus agents review the diff independently; fix and resubmit until both approve.
6. Report reviewer findings and resolutions. The owner runs all git commands.

## Environment notes

- Docker works in a shell that has the `docker` group. If a session lacks it, prefix with `sg docker -c "..."`.
- Nothing is installed on the host; every tool runs in a container through the Makefile.

## Slice 0 (tooling, Makefile, compose dev, PR pipeline)

Last updated: 2026-11-16

| Step | State |
|---|---|
| Brief written | done |
| Opus design challenge | done, 16 findings folded in as amendments A1 to A29 |
| API implementation (`services/api/`) | done, verified in containers by the agent |
| Web implementation (`apps/web/`) | done, verified in containers by the agent |
| Platform implementation (Makefile, compose, CI, Renovate, README, ADR) | done |
| Integrated `make ci` and `make dev` by orchestrator | done: `make ci` passes; `make dev` serves the page and `/api/status` through `localhost:5173` |
| Fable review of the diff | approved in round 2 |
| Opus review of the diff | approved in round 2 |
| Owner commits | done: commit `6f9dbd8` on `main`, pushed to `origin` (includes slice 0, the test suite and the round 1 review fixes; only this file changed afterwards) |

### Changes to the brief made during implementation

- A7 revised: Testcontainers without a host override failed on Docker Desktop (`Could not connect to Ryuk at 172.17.0.1`). The API test target uses `--add-host=host.docker.internal:host-gateway` and `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`. Unverified on GitHub `ubuntu-24.04` runners until the first CI run.
- pnpm 12 reads the store location from `pnpm_config_store_dir`, not `npm_config_store_dir`. `minimumReleaseAge` and `packageImportMethod` live in `apps/web/pnpm-workspace.yaml`.
- TypeScript: `typescript-eslint` does not support TypeScript 7 yet. `tsc` is 7.0.2 (alias `typescript7`); ESLint uses `@typescript/typescript6` 6.0.2 through the `typescript` alias. Revisit when typescript-eslint supports 7.
- Web versions below the registry latest because of the 7-day release-age gate: Vite 8.3.6, ESLint 10.14.0, `@vitejs/plugin-react` 6.2.1.
- nginx image: `nginxinc/nginx-unprivileged:1.30.6-alpine-slim`.
- The web runtime image runs `apk upgrade --no-cache` (hadolint DL3017 ignored) because the pinned nginx base had fixable HIGH CVEs in musl and zlib that failed the Trivy gate. Remove when a base image with the fixes is pinned.
- `trivy convert` has no `--ignore-unfixed`; it is applied at scan time, so the SARIF also omits unfixed findings.
- Trivy mounts `.cache/m2/repository` read-only so `trivy fs` resolves `pom.xml` locally instead of hitting Maven Central (429 rate limit).
- Valkey image: `valkey/valkey:9.1.3-alpine3.24` (9.2 has only rc version tags).
- `@ServiceConnection(name = "postgres")` is required in `ApplicationIT` because the image ref carries a digest.

### Review round 1 findings and resolutions

- CodeQL private-repo guard could pass on `schedule` events with a null payload (Opus): both workflows now compare `github.event.repository.visibility == 'public'`, which is false when the payload is missing.
- CI cached ~3 GB of Trivy data under a key that never refreshed (Opus): split into a deps cache (m2, pnpm) and a date-keyed Trivy cache.
- `make scan` on a fresh clone created a root-owned `.cache/m2/repository` (both): `cache-dirs` now creates it.
- TypeScript 6/7 alias undocumented (both): README known limits and ADR.
- Nits fixed: `ENV CI=true` comment; `WEB_TOOLS_IMAGE` and `MVN_ARGS` exported to compose; scan tarball removed before the gate; ADR host-UID wording and `apk upgrade` exception; README sentence; `defineConfig` in ESLint config; clear error when the socket GID probe fails; hadolint, actionlint, zizmor run as host user; CodeQL no longer skipped for fork PRs.
- Not changed: `@ServiceConnection(name = "postgres")` stays (needed with digest refs, observed by the API agent).

### Round 2 nits deferred (both reviewers approved; none blocking)

- Trivy cache: restore on PRs, save only on push to main, to stay under GitHub's 10 GB cache limit (Opus).
- Scan tarball in `build/images/` survives if the Trivy scan step itself errors; `make clean` removes it (both).
- Add `# check=skip=InvalidDefaultArgInFrom` to both Dockerfiles to silence the expected BuildKit warning (Opus).
- After the repo is public, confirm the first weekly scheduled CodeQL run is not skipped; if it is, the schedule payload lacks `visibility` and the guard needs `github.event_name == 'schedule' ||` (Fable).

### Open items for the owner

- Done 2026-11-17: slice 0 committed and pushed; the `CI` workflow passed on GitHub, which confirms the Testcontainers host override on a Linux runner.

- Code scanning (Trivy SARIF upload, CodeQL) only runs on a public repo. Workflows skip those steps while the repo is private. Decide whether to make the repo public now or at slice 6.
- Install the Renovate GitHub app on the repo once it exists on GitHub.

## To resume slice 0 in a new session

1. Read `master-plan.md`, `slice-0-brief.md` (amendments override the body) and this file.
2. Check which files exist against the layout in the brief; finish any platform files that are missing.
3. Run `make ci`, then `make dev` and open `http://localhost:5173`.
4. Run the two independent reviews, fix findings, update this table.

## Slice 0 follow-up: test suite (brief: `slice-0-tests-brief.md`)

Requested by the owner on 2026-11-16: 100% unit coverage, integration and end-to-end tests, `tests/` directory with README, manual write-ups, README for this directory.

| Step | State |
|---|---|
| Brief written | done |
| Opus design challenge | done, 15 findings folded in as amendments T1 to T17 |
| Implementation (api, web, e2e, platform agents) | done |
| Integrated `make ci` by orchestrator | done: passes in about 72 s warm (API 3 unit + 8 integration, web 18 tests at 100%, 6 e2e scenarios) |
| Fable review | approved in round 2 |
| Opus review | approved in round 2 |
| Owner commits | done: commit `6f9dbd8` on `main`, pushed to `origin` (includes slice 0, the test suite and the round 1 review fixes; only this file changed afterwards) |

### Notes from implementation

- No custom Playwright image: `tests/e2e` installs and lints in `shop/web-tools:dev`; the stock Playwright image only runs the tests.
- JaCoCo appends to `target/jacoco.exec`, so a `maven-clean-plugin` execution deletes it at `initialize`; otherwise stale data hides missing coverage.
- The API has no branch instructions yet, so the 100% branch rule passes trivially until branching code exists.
- `/actuator` on the management port returns 200 and lists only health links; asserted in `ManagementEndpointsIT`, production config unchanged.
- e2e healthchecks use `127.0.0.1`: busybox `wget` resolves `localhost` to `::1`, which nginx-unprivileged does not listen on.
- MCR tags carry no release timestamp, so Renovate would hold the Playwright image forever under the 7-day gate; a scoped rule sets `timestamp-optional` for that image. The image can move up to 7 days before the npm package, and that PR fails the version-lock guard until npm matures (in the manual checklist).
- The same Renovate dry run showed `maven` and `nginxinc/nginx-unprivileged` updates pending for lack of a timestamp; not changed, to be decided.

### Test follow-up review round 1: findings and resolutions

- Manual checklist command to stop the API failed outside `make` (both): added `make api-stop` and `make api-start`, used in the checklist.
- `main.test.tsx` render test passed by accident: one shared `Response` was consumed twice under StrictMode, leaving the app in the unavailable state (Fable): the mock now returns a fresh response per call and the test asserts no alert.
- Nits fixed: e2e teardown survives a failed log capture and Ctrl-C (trap); `E2E_ARGS` for running part of the suite; tautological `toBeDefined`; timeouts in the IT HTTP helper; `ApplicationIT` asserts the exact build version; duplicate e2e assertion; e2e source mounted read-only; README wording (JUnit, `vitest run --coverage`, text report); manual checklist (revert the hot-reload edit, `make e2e-deps`, deferred TODOs removed, Renovate base-image check added); local-only path removed from `docs/plans/README.md`.
- Not changed: the React root in `main.test.tsx` is not unmounted (harmless, Opus nit).

### Test follow-up round 2 nits deferred (both reviewers approved; none blocking)

- `make e2e` on Ctrl-C: the trap tears the stack down before the log line runs, so `stack.log` is emptied and `down` runs twice. Fix: `trap : INT TERM` so the normal log-then-down path runs once (both).
- The runner command `./node_modules/.bin/playwright test` is written in both the Makefile and `tests/e2e/compose.yaml`; make it the compose entrypoint and pass only `$(E2E_ARGS)` (Fable).
- `App.test.tsx` pending-abort test waits 50 ms of real time; fake timers would remove the wall-clock dependency (Fable).
- Two `make e2e` runs in the same checkout collide (fixed project name `shop-e2e`, shared report folder). One run per checkout, or add `E2E_PROJECT ?= shop-e2e` (Opus).

## Slice 0 wrap-up

Slice 0 and the test follow-up are committed and pushed, and the GitHub checks passed (owner confirmed 2026-11-17). The owner works through the rest of `tests/manual/README.md`. Git and GitHub operations (branches, commits, pushes, PRs) are the owner's; Claude edits the working tree only and supplies the commands.

## Slice 1: catalog browsing end to end (brief: `slice-1-brief.md`, branch `slice-1-catalog`)

Started 2026-11-17. The owner asked to run until the daily usage limit; this table is updated at every step so a new session can continue from the first row that is not done.

| Step | State |
|---|---|
| Brief written | done |
| Opus design challenge | done, 27 findings |
| Amendments folded into the brief | done (S1 to S25); owner approved the two coverage exclusions (route tree, shadcn `components/ui`) and wants to be asked before any other |
| Phase 1 implementation: API, WEB, PLATFORM agents in parallel | done. API verified inline by the orchestrator at the owner's request after the API agent was stopped: `make api-lint`, `make api-test` (43 unit, 46 integration, 100% line and branch), `make api-build`, `make api-spec` (spec unchanged, structurally matches the contract), and runtime checks of the built image with profile `seed` (endpoints, sorts, paging, 400 and 404, `nosniff`, `Cache-Control: no-cache`, JSON logs with `traceId` from `traceparent`, no warnings or export errors after 65 s idle, `/v3/api-docs` 404, uid 10001) |
| Integration: `make generate` (generated files unchanged), `make lint test build` | done, all pass (API 43 unit + 46 integration, web 82 tests, both at 100%) |
| Phase 2 implementation: E2E agent | done: 37 Playwright tests (catalog, pagination, product, status, outage, CSP, headers, accessibility). It found a real defect: Zod's eval probe (`new Function`) caused one CSP `script-src` violation per page load; fixed by the orchestrator with `z.config({ jitless: true })` in `apps/web/src/lib/catalogSearch.ts` |
| Integrated `make ci` and `make dev` by orchestrator | done: `make ci` passes in about 150 s (API 43 unit + 46 integration, web 82 tests, both 100%; scans clean; 37 e2e passed with zero CSP violations). `make dev` serves the seeded catalog through `localhost:5173`; not opened in a real browser from here |
| Review | approved by Opus in round 3 (rounds 1 and 2 requested changes, all fixed). `make ci` green: API 43 unit + 58 integration, web 88 tests, both 100%; scans clean; 37 e2e with zero CSP violations |
| Owner commits and opens the PR | done: merged into `main` as PR #1 (`389f354`) on 2026-11-17 |

### How to resume slice 1

1. Read `slice-1-brief.md` (amendments at the end override the body) and this table.
2. `git status` and `git diff --stat main` show what exists. Compare with the brief's work split to see which agent's part is missing or half done.
3. Run `make lint` and `make test` to see what currently passes, then continue from the first unfinished row.
4. Agents must not run git commands other than read-only `git status` and `git diff` (an API agent tried `git rm --cached` on 2026-11-17; it had no effect, nothing was staged). Say so in every agent prompt.

### Slice 1 review round 1: findings and resolutions

- NUL byte in `q`, `category` or the slug reached Postgres and gave a 500 (Fable, should-fix): control characters rejected with `@Pattern` on all three, `@Size(max = 100)` added to `category` and the slug, integration tests for each.
- Deleted `specs/home.spec.ts` still named in the Makefile help, README, tests/README.md and the manual checklist (Opus, should-fix): now `specs/catalog.spec.ts`.
- Manual keyboard and browser checks described the old home page (Opus, should-fix): rewritten for the catalog, product page and `/status`.
- Nits fixed: sort select shows "Name" for an unsearched list (both); e2e prices found by `data-testid` with a non-empty check (Fable); seed upsert updates `currency` (Fable); ADR 0003 notes the `ProblemDetail` exception to required properties, that openapi-typescript and TanStack Router updates also need `make generate`, and the count/page snapshot caveat for slice 3 (both); `ModularityTest` moved to the unit row in tests/README.md (Opus); unused `lucide-react` and the `shadcn` CLI removed from dependencies (Opus); `.tanstack/` ignored (Opus); `make generate` runs its steps in sequence (Opus); CSP collector waits for the page to settle before asserting (Opus); `check-routes.sh` always restores the committed tree (Opus).
- Not changed: an unknown `category` slug in the URL shows "All categories" in the select while results say "No products match" (Opus nit, deferred to slice 1 polish or later).

### Slice 1 review round 2 (Opus): findings and resolutions

- Malformed percent-encoding in a query parameter (`q=%FF`) gave a 500 and an ERROR stack trace (should-fix): `InvalidParameterException` now maps to a 400 Problem Details, logged at debug; tests for `q=%FF`, `q=%ED%A0%80`, `category=%FF`.
- The OpenAPI `pattern` `\p{Cntrl}` was Java-only (should-fix): now `^[^\x00-\x1F\x7F]*$`, valid in Java, ECMA-262 and Python; spec regenerated.
- The slug control-character test could not fail because Tomcat rejected `%00` first (should-fix): now `oak%0Adesk` and `%7F`, asserting `application/problem+json`.
- Nits fixed: 200 cases for `brass lamp` and `café`; the web app drops control characters and over-long categories from URL params, and an API 400 on a product slug shows "Product not found"; ADR 0002 documents the pinned shadcn CLI command and the `lucide-react` caveat; ADR 0003 notes that Tomcat-rejected requests get Tomcat's HTML 400 page.

### Slice 1 round 3 nits deferred (Opus approved; none blocking)

- `ApiExceptionHandler` says "not valid UTF-8" for every `InvalidParameterException`, which Tomcat also throws for empty parameter names, bad hex and the parameter-count limit; use a neutral detail such as "A request parameter could not be parsed" and add `=x` and `q=%G1` to the bad-request test.
- `ApiExceptionHandler` imports a Tomcat class; note in ADR 0003 that switching servlet containers means revisiting it.
- `hasNoControlCharacters` in `apps/web/src/lib/catalogSearch.ts` can iterate UTF-16 units directly; add a test that U+0085, U+00A0 and an emoji are accepted.
- From round 1: an unknown `category` slug in the URL shows "All categories" in the select.

## Next

Slice 1 is merged (PR #1). Next is slice 2 (deploy slice 1 to kind): write `slice-2-brief.md` here first. Before slice 3, create the repo-specific `CLAUDE.md` described in `master-plan.md` ("Repo-specific `CLAUDE.md`"): a second, security-only Opus reviewer for slices 3, 4 and 6, plus the standing agent rules. Per the global CLAUDE.md (updated 2026-11-17): Opus designs, an Opus agent challenges, Sonnet implements, one Opus agent reviews until it approves.
