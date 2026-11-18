# Status and resume notes

This directory is the source of truth for plans. `master-plan.md` here supersedes the original at `~/.claude/plans/splendid-noodling-dewdrop.md`; update this copy, not that one.

- `master-plan.md`: the overall plan and build order (slices 0 to 6).
- `slice-N-brief.md`: the implementation brief for each slice, including amendments from the design challenge.
- `STATUS.md`: this file. Updated at every step change.

## Process per slice

All agents are Opus except the Sonnet implementers.

1. An agent reads the code, checks versions against registries and docs, and writes `slice-N-brief.md`.
2. A fresh agent challenges the brief. High-risk slices (3, 4, 6) also get a second, independent challenger with a security and failure-mode lens. Accepted findings are appended as amendments.
3. Sonnet agents implement in parallel on non-overlapping files.
4. The orchestrator runs the integrated checks (`make ci`, `make dev`).
5. A fresh agent reviews the diff; fix and resubmit until it approves. Slices 3, 4 and 6 add a security-only reviewer, and both must approve.
6. Report reviewer findings and resolutions. The owner runs all git commands.

## Environment notes

- Docker works in a shell that has the `docker` group. If a session lacks it, prefix with `sg docker -c "..."`.
- Nothing is installed on the host; every tool runs in a container through the Makefile.

## Slice 0 (tooling, Makefile, compose dev, PR pipeline)

Last updated: 2026-11-16

| Step | State |
|---|---|
| Brief written | done |
| Design challenge | done, 16 findings folded in as amendments A1 to A29 |
| API implementation (`services/api/`) | done, verified in containers by the agent |
| Web implementation (`apps/web/`) | done, verified in containers by the agent |
| Platform implementation (Makefile, compose, CI, Renovate, README, ADR) | done |
| Integrated `make ci` and `make dev` by orchestrator | done: `make ci` passes; `make dev` serves the page and `/api/status` through `localhost:5173` |
| Review of the diff | approved in round 2 |
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

- CodeQL private-repo guard could pass on `schedule` events with a null payload: both workflows now compare `github.event.repository.visibility == 'public'`, which is false when the payload is missing.
- CI cached ~3 GB of Trivy data under a key that never refreshed: split into a deps cache (m2, pnpm) and a date-keyed Trivy cache.
- `make scan` on a fresh clone created a root-owned `.cache/m2/repository`: `cache-dirs` now creates it.
- TypeScript 6/7 alias undocumented: README known limits and ADR.
- Nits fixed: `ENV CI=true` comment; `WEB_TOOLS_IMAGE` and `MVN_ARGS` exported to compose; scan tarball removed before the gate; ADR host-UID wording and `apk upgrade` exception; README sentence; `defineConfig` in ESLint config; clear error when the socket GID probe fails; hadolint, actionlint, zizmor run as host user; CodeQL no longer skipped for fork PRs.
- Not changed: `@ServiceConnection(name = "postgres")` stays (needed with digest refs, observed by the API agent).

### Round 2 nits deferred (approved; none blocking)

- Trivy cache: restore on PRs, save only on push to main, to stay under GitHub's 10 GB cache limit.
- Scan tarball in `build/images/` survives if the Trivy scan step itself errors; `make clean` removes it.
- Add `# check=skip=InvalidDefaultArgInFrom` to both Dockerfiles to silence the expected BuildKit warning.
- After the repo is public, confirm the first weekly scheduled CodeQL run is not skipped; if it is, the schedule payload lacks `visibility` and the guard needs `github.event_name == 'schedule' ||`.

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
| Design challenge | done, 15 findings folded in as amendments T1 to T17 |
| Implementation (api, web, e2e, platform agents) | done |
| Integrated `make ci` by orchestrator | done: passes in about 72 s warm (API 3 unit + 8 integration, web 18 tests at 100%, 6 e2e scenarios) |
| Review | approved in round 2 |
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

- Manual checklist command to stop the API failed outside `make`: added `make api-stop` and `make api-start`, used in the checklist.
- `main.test.tsx` render test passed by accident: one shared `Response` was consumed twice under StrictMode, leaving the app in the unavailable state: the mock now returns a fresh response per call and the test asserts no alert.
- Nits fixed: e2e teardown survives a failed log capture and Ctrl-C (trap); `E2E_ARGS` for running part of the suite; tautological `toBeDefined`; timeouts in the IT HTTP helper; `ApplicationIT` asserts the exact build version; duplicate e2e assertion; e2e source mounted read-only; README wording (JUnit, `vitest run --coverage`, text report); manual checklist (revert the hot-reload edit, `make e2e-deps`, deferred TODOs removed, Renovate base-image check added); local-only path removed from `docs/plans/README.md`.
- Not changed: the React root in `main.test.tsx` is not unmounted (harmless nit).

### Test follow-up round 2 nits deferred (approved; none blocking)

- `make e2e` on Ctrl-C: the trap tears the stack down before the log line runs, so `stack.log` is emptied and `down` runs twice. Fix: `trap : INT TERM` so the normal log-then-down path runs once.
- The runner command `./node_modules/.bin/playwright test` is written in both the Makefile and `tests/e2e/compose.yaml`; make it the compose entrypoint and pass only `$(E2E_ARGS)`.
- `App.test.tsx` pending-abort test waits 50 ms of real time; fake timers would remove the wall-clock dependency.
- Two `make e2e` runs in the same checkout collide (fixed project name `shop-e2e`, shared report folder). One run per checkout, or add `E2E_PROJECT ?= shop-e2e`.

## Slice 0 wrap-up

Slice 0 and the test follow-up are committed and pushed, and the GitHub checks passed (owner confirmed 2026-11-17). The owner works through the rest of `tests/manual/README.md`. Git and GitHub operations (branches, commits, pushes, PRs) are the owner's; Claude edits the working tree only and supplies the commands.

## Slice 1: catalog browsing end to end (brief: `slice-1-brief.md`, branch `slice-1-catalog`)

Started 2026-11-17. The owner asked to run until the daily usage limit; this table is updated at every step so a new session can continue from the first row that is not done.

| Step | State |
|---|---|
| Brief written | done |
| Design challenge | done, 27 findings |
| Amendments folded into the brief | done (S1 to S25); owner approved the two coverage exclusions (route tree, shadcn `components/ui`) and wants to be asked before any other |
| Phase 1 implementation: API, WEB, PLATFORM agents in parallel | done. API verified inline by the orchestrator at the owner's request after the API agent was stopped: `make api-lint`, `make api-test` (43 unit, 46 integration, 100% line and branch), `make api-build`, `make api-spec` (spec unchanged, structurally matches the contract), and runtime checks of the built image with profile `seed` (endpoints, sorts, paging, 400 and 404, `nosniff`, `Cache-Control: no-cache`, JSON logs with `traceId` from `traceparent`, no warnings or export errors after 65 s idle, `/v3/api-docs` 404, uid 10001) |
| Integration: `make generate` (generated files unchanged), `make lint test build` | done, all pass (API 43 unit + 46 integration, web 82 tests, both at 100%) |
| Phase 2 implementation: E2E agent | done: 37 Playwright tests (catalog, pagination, product, status, outage, CSP, headers, accessibility). It found a real defect: Zod's eval probe (`new Function`) caused one CSP `script-src` violation per page load; fixed by the orchestrator with `z.config({ jitless: true })` in `apps/web/src/lib/catalogSearch.ts` |
| Integrated `make ci` and `make dev` by orchestrator | done: `make ci` passes in about 150 s (API 43 unit + 46 integration, web 82 tests, both 100%; scans clean; 37 e2e passed with zero CSP violations). `make dev` serves the seeded catalog through `localhost:5173`; not opened in a real browser from here |
| Review | approved in round 3 (rounds 1 and 2 requested changes, all fixed). `make ci` green: API 43 unit + 58 integration, web 88 tests, both 100%; scans clean; 37 e2e with zero CSP violations |
| Owner commits and opens the PR | done: merged into `main` as PR #1 (`389f354`) on 2026-11-17 |

### How to resume slice 1

1. Read `slice-1-brief.md` (amendments at the end override the body) and this table.
2. `git status` and `git diff --stat main` show what exists. Compare with the brief's work split to see which agent's part is missing or half done.
3. Run `make lint` and `make test` to see what currently passes, then continue from the first unfinished row.
4. Agents must not run git commands other than read-only `git status` and `git diff` (an API agent tried `git rm --cached` on 2026-11-17; it had no effect, nothing was staged). Say so in every agent prompt.

### Slice 1 review round 1: findings and resolutions

- NUL byte in `q`, `category` or the slug reached Postgres and gave a 500 (should-fix): control characters rejected with `@Pattern` on all three, `@Size(max = 100)` added to `category` and the slug, integration tests for each.
- Deleted `specs/home.spec.ts` still named in the Makefile help, README, tests/README.md and the manual checklist (should-fix): now `specs/catalog.spec.ts`.
- Manual keyboard and browser checks described the old home page (should-fix): rewritten for the catalog, product page and `/status`.
- Nits fixed: sort select shows "Name" for an unsearched list; e2e prices found by `data-testid` with a non-empty check; seed upsert updates `currency`; ADR 0003 notes the `ProblemDetail` exception to required properties, that openapi-typescript and TanStack Router updates also need `make generate`, and the count/page snapshot caveat for slice 3; `ModularityTest` moved to the unit row in tests/README.md; unused `lucide-react` and the `shadcn` CLI removed from dependencies; `.tanstack/` ignored; `make generate` runs its steps in sequence; CSP collector waits for the page to settle before asserting; `check-routes.sh` always restores the committed tree.
- Not changed: an unknown `category` slug in the URL shows "All categories" in the select while results say "No products match" (nit, deferred to slice 1 polish or later).

### Slice 1 review round 2: findings and resolutions

- Malformed percent-encoding in a query parameter (`q=%FF`) gave a 500 and an ERROR stack trace (should-fix): `InvalidParameterException` now maps to a 400 Problem Details, logged at debug; tests for `q=%FF`, `q=%ED%A0%80`, `category=%FF`.
- The OpenAPI `pattern` `\p{Cntrl}` was Java-only (should-fix): now `^[^\x00-\x1F\x7F]*$`, valid in Java, ECMA-262 and Python; spec regenerated.
- The slug control-character test could not fail because Tomcat rejected `%00` first (should-fix): now `oak%0Adesk` and `%7F`, asserting `application/problem+json`.
- Nits fixed: 200 cases for `brass lamp` and `café`; the web app drops control characters and over-long categories from URL params, and an API 400 on a product slug shows "Product not found"; ADR 0002 documents the pinned shadcn CLI command and the `lucide-react` caveat; ADR 0003 notes that Tomcat-rejected requests get Tomcat's HTML 400 page.

### Slice 1 round 3 nits deferred (approved; none blocking)

- `ApiExceptionHandler` says "not valid UTF-8" for every `InvalidParameterException`, which Tomcat also throws for empty parameter names, bad hex and the parameter-count limit; use a neutral detail such as "A request parameter could not be parsed" and add `=x` and `q=%G1` to the bad-request test.
- `ApiExceptionHandler` imports a Tomcat class; note in ADR 0003 that switching servlet containers means revisiting it.
- `hasNoControlCharacters` in `apps/web/src/lib/catalogSearch.ts` can iterate UTF-16 units directly; add a test that U+0085, U+00A0 and an emoji are accepted.
- From round 1: an unknown `category` slug in the URL shows "All categories" in the select.

## Next

Slices 1 and 2 and the repo `CLAUDE.md` are merged (PR #1, #2, #3). Slice 3 is in progress; see its section at the end.

## Slice 2: deploy slice 1 to kind (brief: `slice-2-brief.md`, branch `slice-2-kind-deploy`)

Started 2026-11-17. Owner decisions: cluster e2e runs in CI as a separate parallel job on every PR; NetworkPolicies (default deny, allow rules, denied-connection test) are in this slice; the front door is `http://localhost:<port>` without TLS (localhost is a secure context, so slice 3's `__Host-` cookie works; TLS and HSTS stay deferred). One challenger and one reviewer (not a high-risk slice).

| Step | State |
|---|---|
| Brief written | done (232 lines). Key choices: Calico replaces kindnet (kindnet fails open on this WSL kernel, lacks `CONFIG_NFT_QUEUE`); kind node 1.36.4 for CNPG support; front door `localhost:8088`; Postgres 18.6 under the 7-day gate. Docker Desktop was not running, so nothing was tried in containers |
| Design challenge | done, 11 findings (1 blocker: provenance attestations change `:dev` image IDs on every build, so every deploy would roll pods) |
| Amendments folded into the brief | done (B1 to B11). B2 makes the browser origin `http://localhost:8088` in both e2e stacks via `--host-resolver-rules`, so slice 3 `__Host-` cookies work in e2e |
| Implementation: K8S, PLATFORM, E2E and DOCS agents in parallel | done. Live run fixed three K8S issues: kubelet 1.36 refuses cgroup v1 (Docker Desktop here; `failCgroupV1: false`), calico-node `/sys/kernel/security` mount on a read-only sysfs (removed by patch), EnvoyProxy in `default` instead of `shop`. K8S agent then ran `cluster-up deploy cluster-smoke cluster-e2e` green: 40 Playwright, 9 of 9 NetworkPolicy checks |
| Integrated `make lint`, `make ci`, `make cluster-up deploy cluster-e2e` by orchestrator | done: `make lint` and `make ci` pass (API 58 integration, web 88, both 100%; scans clean; 40 compose e2e). `make cluster-down cluster-up deploy cluster-smoke cluster-e2e` passes from scratch in 217 s (40 Playwright, 9 of 9 NetworkPolicy checks). A second `make deploy` left api, web and shop-db-1 pods unchanged (B1 confirmed) |
| Review | round 1: request changes (4 should-fix, 7 nits), all fixed and re-verified (`make lint`, `make ci` with 42 compose e2e, cluster from scratch in 220 s with 42 Playwright and 9 of 9 NetworkPolicy checks, rerun of `cluster-up deploy` skips both Helm releases and restarts no pods). Round 2: request changes (1 should-fix: ADR 0005 overstated Docker socket scope; 7 nits), fixed and re-verified (lint, ci, cluster from scratch). Approved in round 3 (only deferrable nits) |
| Owner commits and opens the PR; first `cluster-e2e` CI run | done: PR #2 opened 2026-11-18. First run green on ubuntu-24.04 (cgroup v2): `cluster-e2e` 6 min 8 s (42 Playwright, 9 of 9 NetworkPolicy checks), `ci` 4 min 34 s, CodeQL 1 min 14 s. Merged into `main` as PR #2 (`24ddfd0`) |

### Slice 2 review round 1: findings and resolutions (all fixed)

- `cluster-e2e` skipped diagnostics when the NetworkPolicy test failed, because `-euo pipefail` exits on the piped line (should-fix): wrap the pipeline in `{ ...; } || status=1`.
- No diagnostics when `cluster-up` fails or the CI job times out (should-fix): CI step `if: failure() || cancelled()` runs `make cluster-diagnostics` before the upload.
- Docs contradicted the code (should-fix): ADR 0001 socket scope, the cgroup v1 workaround missing from ADR 0005 and README, wrong claim about the Calico age gate.
- Renovate would open Calico PRs that change only a comment (should-fix): `github-tags` datasource capturing both the version and the commit digest.
- Nits being fixed: `db-egress` 6443 breadth documented; probe namespace PSS labels pinned to v1.36; Makefile kubectl-version comment; Helm skip now also compares values; ESLint rule forcing specs to use `./support/test`; bare `/api` routed the same in both stacks; slice 3 note on `X-Forwarded-*` trust behind Envoy; `renovate.json` formatting churn reverted.

### Slice 2 review round 2: findings and resolutions (all fixed)

- ADR 0005 said kind, Helm and kubectl all get the Docker socket; only kind does (should-fix): ADR 0005 and 0001 corrected.
- Nits being fixed: `--pull=never` on the kind and kubectl helpers so a missing `shop/k8s-tools:dev` is never pulled from Docker Hub; `kubectl` target depends on `cache-dirs`; `tests/README.md` routing line; README known limits name the kind targets' socket use; one constant for `localhost:8088`; brief corrected as amendment B12.
- Not changed: CI diagnostics step stays on `failure() || cancelled()`, so a deploy or e2e failure exports kind logs twice (harmless; `cluster-up` has no failure handler of its own).

### Slice 2 round 3 nits deferred (approved; none blocking)

- `cluster-up` restarting a stopped kind node goes straight to the Calico apply and fails while the API server comes back; a second run works. Fix: poll `kubectl get --raw /readyz` (bounded) after `docker start`.
- `tests/e2e/specs/routing.spec.ts:7` hardcodes `http://localhost:8088`; import `frontDoorOrigin`.
- Calico images and the Envoy Gateway and CNPG charts are pinned by tag or version, not digest; revisit in slice 6.
- Renovate can bump kind without its `kindest/node` digest (the node image rule is disabled); add a PR note or disable kind too. `cluster-e2e` would catch a real incompatibility.
- Done: the `cluster-e2e` job passed on GitHub `ubuntu-24.04` (cgroup v2) in 6 min 8 s on its first run.

## Repo CLAUDE.md (branch `docs-repo-claude-md`)

Started 2026-11-17. Writes the repo-root `CLAUDE.md` described in `master-plan.md` ("Repo-specific `CLAUDE.md`"). Documentation only, so one reviewer and no design challenge.

| Step | State |
|---|---|
| `CLAUDE.md` written from `master-plan.md`, this file, `docs/plans/README.md`, `README.md` and the coverage-exclusion decision | done (70 lines) |
| Review | round 1: request changes (2 should-fix, 3 nits), all fixed. Approved in round 2 |
| Owner commits and pushes | done: merged into `main` as PR #3 (`aaae365`) |

### Review round 1: findings and resolutions (all fixed)

- Said `make lint` catches all stale generated files (should-fix): lint catches the web client and route tree; a stale `openapi.json` fails `OpenApiContractIT` in `make test`.
- Said `make ci` is everything the PR workflow runs (should-fix): CI also runs `make cluster-up deploy cluster-e2e` as a second job, so the integration step now runs the cluster targets whenever app, deploy or e2e code changes.
- Nits fixed: Vite 8 plugin compatibility added to the traps; hand-bumped pins in `deploy/versions.mk` (`kindest/node` digest, schema refs) called out; coverage gates stated per app (API line and branch; web lines, branches, functions, statements).

## Slice 3: auth (brief: `slice-3-brief.md`, branch `slice-3-auth`)

Started 2026-11-17. High-risk slice: two independent challengers (general; security and failure-mode) and two reviewers (general; security-only), per `CLAUDE.md`.

| Step | State |
|---|---|
| Brief written | done (229 lines). Versions read 2026-11-17; owner decisions D1 to D10 listed in the brief |
| Design challenge (general) | done: 1 blocker (work split not file-disjoint: Makefile, ci.yaml, diagnostics.sh), 11 should-fix, 8 nits; not yet folded in |
| Design challenge (security and failure-mode) | done: 1 blocker (back-channel logout and role revocation lapse after Keycloak's 30-min SSO idle), 10 should-fix, 9 nits; not yet folded in |
| Amendments folded into the brief; owner decisions | owner decisions taken 2026-11-17: D1 to D10 as recommended, plus the security challenger's adjustments (D5 gate CRITICAL with expiring ignores, HIGH reported until slice 6; D8 anonymous 401, authenticated 403; D9 JEP 290 `ObjectInputFilter` allowlist; D4 fail-fast placeholders and per-container env); D11 = revalidate with Keycloak every 5 min of activity (refresh, re-map roles, fail closed on `invalid_grant`, single refresh per session). D3 kept, with `strategy: Recreate`. D12 = scoped `timestamp-optional` Renovate rule for `quay.io/keycloak/keycloak` (as for Playwright). D13 = two PRs: 3a (Keycloak in all stacks, BFF login/logout, sessions, CSRF, `/api/me`, one admin read endpoint) on `slice-3-auth`, then 3b (admin writes, stock, audit, admin forms) on a new branch. Rationale doc: https://claude.ai/artifact/5UfXP9VaCRhDCzmkWAZJcM. Amendments not yet folded in |
| Implementation | not started |
| Integrated `make ci` and `make cluster-up deploy cluster-e2e` | not started |
| Review (general) | not started |
| Review (security-only) | not started |
| Owner commits and opens the PR | not started |
