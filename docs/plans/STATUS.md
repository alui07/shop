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

Slices 1, 2, 3a and 3b and the repo `CLAUDE.md` are merged (PR #1 to #6). Slice 4 is in progress on `slice-4-checkout`; see its section at the end. Open follow-up: undo the temporary scan-web exception once nginx 1.30.7 is published (see slice 3b).

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
| Amendments folded into the brief; owner decisions | owner decisions taken 2026-11-17: D1 to D10 as recommended, plus the security challenger's adjustments (D5 gate CRITICAL with expiring ignores, HIGH reported until slice 6; D8 anonymous 401, authenticated 403; D9 JEP 290 `ObjectInputFilter` allowlist; D4 fail-fast placeholders and per-container env); D11 = revalidate with Keycloak every 5 min of activity (refresh, re-map roles, fail closed on `invalid_grant`, single refresh per session). D3 kept, with `strategy: Recreate`. D12 = scoped `timestamp-optional` Renovate rule for `quay.io/keycloak/keycloak` (as for Playwright). D13 = two PRs: 3a (Keycloak in all stacks, BFF login/logout, sessions, CSRF, `/api/me`, one admin read endpoint) on `slice-3-auth`, then 3b (admin writes, stock, audit, admin forms) on a new branch. Rationale doc: https://claude.ai/artifact/5UfXP9VaCRhDCzmkWAZJcM. Amendments C1 to C40 folded into the brief (2026-11-17); C40 defines the 3a scope and work split |
| Implementation (3a): PLATFORM, API, DOCS in phase 1; K8S, WEB, E2E in phase 2 | in progress: phase 1. DOCS drafts done (ADR 0006, 56 lines; threat model auth section, 69 lines; unverified points marked). PLATFORM done: `make lint`, `make secrets` (twice, idempotent), `keycloak-build`, `scan-keycloak` (0 CRITICAL; 1 HIGH reported), `make dev` with Keycloak issuer `http://localhost:8180/realms/shop`; scripted auth-code login gives `roles` in the ID token only. `cluster-up`/`deploy-apply`/`cluster-e2e` changes untested until K8S. API contract published (`/api/me`, `/api/admin/products`), API still running. WEB done: `make web-lint web-build` pass, `make web-test` 150 tests at 100%, no new exclusions. E2E done: `make e2e-lint` passes; `make e2e` 78 passed against the real API, web and Keycloak (new specs: auth, csrf, keycloak, logout, admin, signed-in). C23 confirmed: Keycloak 26.8 accepts an expired `id_token_hint` (compose e2e realm token lifespan 10 s; the C23 spec skips on kind, manual there). Residual: nginx error log may print a URL with query in buffering warnings (assets only seen). K8S done on a fresh cluster: `k8s-lint`, `deploy`, `cluster-smoke` pass; `cluster-e2e` 77 Playwright and 22 of 22 NetworkPolicy checks (6 allowed, 16 denied). Keycloak import init about 89 s, ready 2 to 3 min after pod start; C32 needed no extra emptyDir. Kind node 3.45 to 3.58 GiB with everything running. Envoy logs strip query strings on all paths; `X-Forwarded-Host` removed and `numTrustedHops: 0`. Overlay gained `namespace: shop` (ConfigMap generator fix). A second `make deploy` rolled `api` once (new API image ID while the API agent was still changing code; recheck in integration). API done: `api-lint`, `api-test` (71 unit + 151 integration, 100% line and branch, no new exclusions), `api-build`, `api-spec` (no further diff). Deviations: revalidation calls the refresh-token client directly (`IdentityRefresher`; the authorized-client manager discards the ID token); logout-token decoder keeps Spring's default timeouts; registry cleanup in the `SessionRepository` decorator's `deleteById`; `RequestRejectedHandler` gives 400 problem+json; `/actuator/env` and `/actuator/info` now 401; Spring accepts a future `iat` and gives 500 for a logout token without `events` (session stays alive). DOCS phase 3 done: README (170 lines), tests/README (79), manual checklist (162, new auth section), ADR 0006 accepted with a deviations section, threat model R17 to R19 added. Implementation complete |
| Integrated `make ci` and `make cluster-up deploy cluster-e2e` | `make generate` (generated files unchanged), `make lint` and `make ci` pass (4 min 20 s): API 71 unit + 151 integration, web 100%, scans clean (Keycloak 0 CRITICAL), 78 compose e2e. `make cluster-down cluster-up deploy cluster-smoke cluster-e2e` passes from scratch in 450 s (77 Playwright, 1 skipped (C23 on kind), 22 of 22 NetworkPolicy checks); a second `make deploy` restarts no app pods; kind node 3.3 GiB. A first attempt lost the kind node mid-NetworkPolicy-test (container removed at 19:42:16: the owner ran `make cluster-down` at that time; not a defect) |
| Review (general) | round 1: request changes (3 should-fix: revalidation lock released before the session commit, so a second request can reuse a rotated refresh token and be signed out; Renovate cannot see the Valkey pin in `valkey.yaml`; the kind manual check for an expired `id_token_hint` cannot expire because revalidation refreshes first. 8 nits). All fixed (see round 1 below). Round 2: request changes (G1 race confirmed closed, and the new IT would fail against the old code; G2 to G10 fixed; 1 should-fix: secrets are applied after the manifests, so pods can roll on stale values, and Valkey has no hash annotation; 3 nits: test counts, a checkbox on a non-check, a fully qualified type). Fixed. Round 3: **approved** (all findings G1 to G11 resolved) |
| Review (security-only) | round 1: request changes (1 should-fix: Keycloak's default `admin-cli` client accepts the password grant on the public token endpoint; 6 nits). Future-`iat` and missing-`events` logout tokens accepted as residual risks R17, R18. All fixed. Round 2: **approved** (S1 to S5 fixed, S7 recorded as R20; secrets hash, Valkey placeholder and 503 declaration checked; 1 availability-only nit: a request that runs over 20 s after its refresh could let a later request reuse the rotated token, a false sign-out, deferred) |
| Owner commits and opens the PR (3a) | ready: both reviewers approved; final `make api-test` after the last change passes (79 unit, 153 integration, 100%). Committed (`e85d6a3`), PR #4 opened 2026-11-18. CodeQL flagged 1 high alert (`java/spring-disabled-csrf-protection`) in the test-only `MethodSecurityDenialTest` filter chain, which disabled CSRF needlessly (GET-only test); line removed, `make api-lint api-test` pass (79 unit, 153 integration, 100%). Fix pushed (`eba3e87`); CodeQL alert cleared. First green CI on PR #4 (2026-11-18): `ci` 6 min 16 s (slice 2: 4 min 34 s), `cluster-e2e` 7 min 41 s (slice 2: 6 min 8 s), CodeQL and Trivy pass. Awaiting owner merge |

### Slice 3a review round 1: findings and resolutions (all fixed and re-verified)

- General G1 (should-fix): revalidation lock released before the session commit; a second request can reuse a rotated refresh token and be signed out. Fix: lock is never deleted, TTL 20 s (also covers security S4, lock expiring mid-call); stub IdP rejects reused refresh tokens; test for a request arriving after the attempt.
- General G2 (should-fix): Renovate cannot see the Valkey pin in `valkey.yaml`. Fix: placeholder image set from `VALKEY_IMAGE` at deploy time, `versions.mk` stays the single pin.
- General G3 (should-fix): the kind manual check for an expired `id_token_hint` cannot expire (revalidation refreshes first). Fix: manual step rewritten, ADR 0006 corrected; compose e2e remains the proof.
- Security S1 (should-fix): `admin-cli` accepts the password grant. Fix: `admin-cli` declared with direct grants off; `keycloak.spec.ts` covers it; threat model A15 covers every client.
- Nits being fixed: JWKS fetch errors use the grace period (G4); null-safe concurrent back-channel logout (G5); login start and login failure only touch unauthenticated sessions (G6, S2); one idle-timeout setting (G7); 503 declared in the OpenAPI document (G8); `logout.spec.ts` uses `frontDoorOrigin` (G9); `tests/README.md` placement (G10); secrets hash annotation restarts api and keycloak when secrets change (G11); refused revalidation clears the CSRF token (S3); refresh without an ID token is `Unavailable` (S5); client-IP trust recorded for slice 5 (S7).
- Not changed: future `iat` and missing `events` on logout tokens (S6) stay residual risks R17, R18 (only Keycloak can sign them; the endpoint is not routed).
- As implemented: the revalidation lock key includes the last-attempt time (`shop:revalidate:<sid>:<lastAttempt>`), TTL 20 s, never deleted; `admin-cli` is disabled outright (the token endpoint answers `invalid_client`); Valkey image is `valkey-image-placeholder` substituted from `VALKEY_IMAGE` in `deploy-apply`; `shop.dev/secrets-hash` annotation (sha256 of the env file) on api and keycloak; `spring.session.timeout` derives from `shop.auth.idle-session-timeout`; `ServiceUnavailableResponseCustomizer` adds the 503 to every operation. The usage limit interrupted the three fix agents once; they resumed without loss.
- Re-verified after the fixes: `make generate` (web client picks up the 503), `make lint`, `make ci` in 216 s (API 153 integration tests, 100% line and branch; web 100%; 78 compose e2e including `admin-cli` refused with `invalid_client`), kind from scratch in 442 s (77 Playwright, 1 skipped, 22 of 22 NetworkPolicy checks), a second `make deploy` restarts no app pods.

### Slice 3a review round 2: findings and resolutions

- General (should-fix): G11 follow-up. Secrets are applied after the manifests that carry the new hash, so `api` and `keycloak` can roll before the Secret updates and stay on old values; Valkey has no hash, so a `VALKEY_PASSWORD` change rolls `api` but not Valkey. Fixed: Namespace and Secrets applied before the main apply; a sha256 per Secret on each Deployment that consumes it (`api`: shop-auth, valkey-auth; `keycloak`: shop-auth, keycloak-test-users; `valkey`: valkey-auth). Verified on kind: `make lint`; an unchanged redeploy restarts nothing; rotating `VALKEY_PASSWORD` rolls valkey and api only, smoke passes, public GET 200 with no or stale cookie; original secrets restored.
- General nits fixed: `tests/README.md` counts (API 79 unit, 153 integration); the C23 kind note is plain text, not a checkbox; `SessionAttributes` imports `HttpSession`.
- Security: approved. One availability-only nit deferred: a request running more than 20 s after its refresh could let a later request reuse the rotated token (false sign-out). Fix if needed later: flush the session immediately after a refresh.

## Slice 3b: admin writes, stock, audit (brief: `slice-3b-brief.md`, branch `slice-3b-admin`)

Started 2026-11-18 after PR #4 and #5 merged 3a. Scope from slice 3 brief C1 and C40 ("Not in 3a"): product and stock writes, audit, V3 to V5, `admin.products.*`, `admin.audit.tsx`, `adminSchemas.ts`, TanStack Form, ADR 0007, `AdminProductIT`, `StockIT`, `AuditIT`, `admin.spec.ts` writes. High-risk slice: two challengers and two reviewers.

| Step | State |
|---|---|
| Brief written | done (2026-11-18). Reconciled with the 3a code (catalog `AdminProduct` split into `ManagedProduct` plus admin `AdminProduct`; `CurrentUser` added to `customer`; validation via `HandlerMethodValidationException`; seed stock and version guard; e2e project ordering). `@tanstack/react-form` 1.33.5 (2026-08-11). Owner decisions D14 to D19 pending |
| Design challenge (general) | done: 0 blockers, 9 should-fix (G1 to G9), 11 nits (G10 to G20); agrees with D14 to D19 (D16 and D19 conditional on G2 and G3). Report `build/reports/slice-3b-challenge-general.md`; folded in as E1 to E32 |
| Design challenge (security and failure-mode) | done: 0 blockers, 8 should-fix (S1 to S8), 8 nits (S9 to S16), 11 residual risks; D14, D15, D16, D18 sound, D17 needs the TRUNCATE gap (S10), D19 needs the reserved guard (S5). Report `build/reports/slice-3b-challenge-security.md`; folded in as E1 to E32 |
| Amendments folded into the brief | done: 32 amendments (E1 to E32; G6/S8 and G3/S5 merged; E32 updates the work split, fixed interfaces and verification), brief now 243 lines. Owner decisions taken 2026-11-18: D14 to D19 as recommended (with G2, S15, S10, G3/S5, S4 conditions); S6 admin writes fail closed with 503 once revalidation is overdue, reads keep the grace; S7 trace id kept and documented as untrusted; all other findings folded in. Rationale: https://claude.ai/artifact/99ajco2b4YKXDQwW4rvbC2 |
| Implementation | done: phase 1 (API-ADMIN, DOCS drafts) started 2026-11-18. DOCS drafts done: ADR 0007 (65 lines, Proposed), threat model admin section W1 to W17 and R21 to R31 (137 lines); 12 "(to verify)" points for phase 3 Contract final 18:47 UTC; phase 2 (WEB, E2E) started. API-ADMIN done: `api-lint`, `api-spec` (no second diff), `api-test` 125 unit + 270 integration at 100% line and branch, `api-build`. Deviations: one fixed-text `ProductNotFoundException`; catch-all logs class and SQLState only (no constraint name: driver is runtime scope); lock timeout matched by SQLState `55P03`; shared `Problems` builder in `web`; CSRF header ANDed with the cookie scheme in OpenAPI; stub IdP no longer consumes the refresh token on 5xx WEB done: `web-lint`, `web-test` 287 tests at 100%, `web-build`; `@tanstack/react-form` 1.33.5 pinned; reset with `keepDefaultValues` (plain reset was undone by `FormApi.update`); one product schema for create and edit; Active unchecked by default on create. Contract gap found: no 200 response on admin GET and PUT by id (web uses a cast); fixed: API declares 200 `AdminProduct` (api-test still 100%), web client regenerated, cast removed, `web-lint web-test web-build` pass (287 tests, 100%). E2E done: `e2e-lint`; `make e2e` 85 passed twice in a row (three Playwright projects; `admin-writes` 10 steps; CSP and axe on new admin pages). It found a likely save/reset race on the edit page (typed text overwritten after the first save); WEB fixing. Phase 3 DOCS done: ADR 0007 Accepted (77 lines) with Deviations; threat model final (137); README (185), tests/README (87; cluster e2e count to fill after integration), manual checklist (195, six admin checks). Audit table shows the actor name only (API returns `sub` too); docs say so. Save race fixed: inputs and Save in a `<fieldset disabled>` while submitting, so the reset lands before they re-enable; `web-test` 289 at 100% |
| Integrated `make ci` and `make cluster-up deploy cluster-e2e` | done. `make generate` leaves the tree unchanged; `make ci` passes (API 270 integration at 100%, web 289 at 100%, scans clean, e2e 85). `make cluster-down cluster-up deploy cluster-smoke cluster-e2e` passes from scratch in 446 s (84 Playwright, 1 skipped (C23), 22 of 22 NetworkPolicy checks); a second `cluster-e2e` on the same database passes (84, 1 skipped; cleanup pages over the first run's leftovers); a second `make deploy` restarts no app pods (only the completed NetworkPolicy probe pods are gone). V3 to V5 applied on the fresh kind database |
| Review (general) | round 1: request changes (3 should-fix: R1 encoded admin path skips the E5 503 (same as security S1); R2 every 503 shows the access message, including lock timeouts; R3 "Product saved" returns after Reload. 6 nits R4 to R9). Report `build/reports/slice-3b-review-general-r1.md`. All fixed (see round 1 below). Round 2: **approved** (R1 to R9 fixed with tests; reran `make api-lint api-test web-lint web-test`; 4 optional nits R10 to R13). Report `build/reports/slice-3b-review-general-r2.md` |
| Review (security-only) | round 1: request changes (1 should-fix: `RevalidationFilter` matches the raw URI, so `/api/%61dmin/...` skips the E5 503 while the URL rule still matches; 5 nits S2 to S6; 6 residual risks). Report `build/reports/slice-3b-review-security-r1.md`. All fixed. Round 2: **approved** (S1 to S6 fixed or accepted as R32 to R35; 2 nits: S7 a misplaced sentence in ADR 0007, S8 the web shows any 503 `detail`, suggest an allowlist). Report `build/reports/slice-3b-review-security-r2.md` |
| Owner commits and opens the PR | ready: both reviewers approved 2026-11-18. Owner committed; PR #6 opened 2026-11-18. First CI run: `ci` failed in `scan-web` (5 HIGH nginx CVEs in the 1.30.6 base, published after the branch passed locally; fixed in nginx 1.30.7, released 2026-11-18, image not yet on Docker Hub); `cluster-e2e` and CodeQL green. Owner chose a temporary exception: `apps/web/.trivyignore.yaml` (5 entries, expire 2026-12-02), `scan-web` passes `--ignorefile`. Review round 1: request changes (2 should-fix: CVE-2026-121810 statement generic, it is HTTP/3 only; README Known limits silent on the exception. Nits: CVE-2026-123634 statement generic; scope entries by purl; 4 unreachable MEDIUM nginx CVEs ungated, cleared by the bump). All fixed. Round 2: **approved** (no new findings; reviewer rescan 0 HIGH/CRITICAL, 5 suppressed). Owner pushed `2c4cb8b`; CI failed in `web-lint`: Prettier in `apps/web` also checks `.trivyignore.yaml` and wants single quotes in the `purls` lists. Fixed with `make fmt` (quote style only, same YAML); `make lint` and local `make ci` pass, fresh-DB rescan still 0 HIGH/CRITICAL with 5 suppressed. Owner pushed `a9fc8e6` (2026-11-19): `ci` green; `cluster-e2e` failed in `api-build` before any test ran: Maven could not fetch `spring-boot-starter-parent:4.1.2` from Central (transient; the `ci` job in the same run resolved it, and `cluster-e2e` passed on `77fba6f` and `2c4cb8b`). Failed job re-run 2026-11-19: green. **Merged** as PR #6 (`71f4443`, 2026-11-19) |

### Temporary scan-web exception (2026-11-18), to undo

Once `nginxinc/nginx-unprivileged:1.30.7-alpine-slim` (or later 1.30.x) is on Docker Hub: bump `NGINX_IMAGE` and its digest in `deploy/versions.mk` by hand (Renovate's 7-day gate would hold it), empty `apps/web/.trivyignore.yaml` (6 entries since 2026-11-19, CVE-2026-118899 added) to `vulnerabilities: []` (drop the temporary comment), remove the matching bullet from README Known limits, run `make scan-web`. The entries expire 2026-12-02, after which CI fails again as a backstop.

### Follow-up after merge: offline API image build (branch `ci-api-build-offline`, 2026-11-19)

CI on `main` after the PR #6 merge failed in `api-build`: `docker build` fetched dependencies from Maven Central with an empty BuildKit cache, and Central returned "not found" for `spring-boot-starter-parent:4.1.2` (second time, after `a9fc8e6`). Fix: `make api-build` packages the jar in the Maven container against the cached `.cache/m2` and extracts the layers; the Dockerfile only copies them; `project.build.outputTimestamp` fixed so image IDs stay stable; ADR 0001 updated. Review r1: request changes (should-fix: a stale `target/api-*.jar` after a version bump; fixed by deleting old jars before packaging). r2: **approved** (static; nits: ADR sentence moved; first build after a pom change still downloads once). Verified: two `make api-build` runs give the same image ID; `make docker-lint` passes; one earlier `api-build` failed with "zip END header not found", likely a concurrent build in `services/api/target`, and passed on rerun. `make ci`: lint, test (API 128 unit and 272 integration, web 294, 100%), build, `scan-fs` and `scan-api` pass; `make e2e` 85 passed with the new API image. Two new base-image CVEs, published after the 3b merge, failed the scans (they would fail `main` too). Owner approved both fixes on this branch (2026-11-19): CVE-2026-114938 (CRITICAL, OpenJDK 21 in Keycloak 26.8.1) fixed by bumping `KEYCLOAK_IMAGE` by hand to 26.8.2 (the 26.8.2-3 rebuild, 2026-11-19; `scan-keycloak` clean); CVE-2026-118899 (HIGH, nginx 1.30.6, TLS listener only, unreachable) added as a sixth temporary `apps/web/.trivyignore.yaml` entry expiring 2026-12-02, README Known limits updated. Review r3 of the combined diff: request changes (blocker B1: the hand-bump comment sat between the `# renovate:` line and `KEYCLOAK_IMAGE :=`, which breaks Renovate's matcher; moved above it). r4: **approved** (the `renovate.json` regex matches all 25 pins, Keycloak 26.8.2 with the new digest). `make ci` passes (API 128 unit and 272 integration, web 294, 100%; all scans pass; e2e 85); `make cluster-up deploy cluster-e2e` passes on the existing cluster with Keycloak 26.8.2 (84 passed, 1 skipped (C23), 22 of 22 NetworkPolicy checks). Ready for the owner to commit.

### Slice 3b review round 1: findings and resolutions

- General R1 and security S1 (should-fix, same defect): `RevalidationFilter` matched the raw URI, so `PUT /api/%61dmin/...` skipped the E5 503 while the URL rule matched. Fixed: `PathPatternRequestMatcher` for `/api/admin/**`; unit and IT cases for the encoded path (503, no audit row).
- General R2 (should-fix): every 503 showed the access message, including lock timeouts. Fixed: a 503 shows the API's fixed `detail` ("Your access cannot be confirmed right now", "The product is busy; try again shortly", "The session store is temporarily unavailable"), with a generic fallback; tests for each.
- General R3 (should-fix): "Product saved" reappeared after a conflict Reload. Fixed: Reload clears the flag; test added.
- Security S2: method-security write tests now use `oidcLogin()` with `ROLE_CUSTOMER`, so they fail without `@PreAuthorize`.
- Security S3: the E5 503 carries `Retry-After: 300`; up to 5 minutes of 503 after Keycloak recovers is documented (R32).
- Security S4: DB errors log only the class and SQLState of the first `SQLException` in the cause chain; W15 matches.
- Security S5: `Propagation.MANDATORY` on product and stock write methods (`MandatoryTransactionIT`); residual risk R35.
- Security S6: visually empty or overlapping admin names accepted as admin-trusted content (R33). No `statement_timeout` recorded as R34; seed rewrites without audit rows added to R30.
- General R4, R5 (e2e): stock column asserted; cleanup only after a confirmed create and never masks the original error. R6: `inventory` may depend only on `web`. R7: seed test renamed to what it checks (a version assertion would depend on test order). R8, R9: ADR 0007 counts replaced by a pointer, JSON 400 shape and the Unicode-table note added.
- Re-verified: `make generate` (no change), `make ci` in 235 s (API 128 unit, 272 integration, 100%; web 294, 100%; scans clean; e2e 85), `make deploy cluster-smoke cluster-e2e` on the existing kind cluster (84 passed, 1 skipped, 22 of 22 NetworkPolicy checks).

### Slice 3b review round 2: findings and resolutions

- Both reviewers approved. Fixed after approval (docs only): R10 and S7, the same misplaced sentence in ADR 0007 (the slice 4 note moved back to the `StockManagement` guards; the 503-detail note moved to the E5 paragraph).
- Deferred to a follow-up (optional nits, no current impact): S8 the web shows any 503 `detail` (allowlist the three known texts or pin every 503 detail with an API test); R11 a DB failure without an SQL cause logs only its class, no stack trace (hard to debug); R12 `Retry-After: 300` also on a 503 from losing the single-flight lock, where a few seconds would do; R13 a commit-time failure that is not a `DataAccessException` would reach the catch-all with its message (no deferred constraint exists today).

## Slice 4: cart, checkout, orders, payment (brief: `slice-4-brief.md`, branch `slice-4-checkout`)

Started 2026-11-19 after PR #6 merged 3b. Scope from the master plan: cart, checkout, stock reservation, orders, fake payment, outbox and email. High-risk slice: two challengers and two reviewers. The brief proposes whether to split it (4a/4b) as slice 3 was.

| Step | State |
|---|---|
| Brief written | done (2026-11-19), 233 lines. Spring Modulith 2.1.2, Mailpit v1.33.1, Boot stays 4.1.2 (newer releases under 7 days old). Recommends a 4a/4b split (D20). Owner decisions D20 to D27 pending |
| Design challenge (general) | done: 3 blockers (G1 expiry-job lock order can deadlock; G2 Modulith 2.1.2 resubmission has no backoff and starves after 10 attempts; G3 e2e checkout specs flaky on the persistent kind DB), 17 should-fix, 9 nits; agrees with D20, D21, D27; D22 to D26 conditional. Report `build/reports/slice-4-challenge-general.md` |
| Design challenge (security and failure-mode) | done: 1 blocker (S1 fake webhook retries get new event ids and no one-way payment status, so one payment can apply twice and refund a PAID order), 12 should-fix, 10 nits, 14 residual risks R36 to R49; agrees with D20, D21, D27; D22, D26 with conditions; D23 to D25 conditional. Report `build/reports/slice-4-challenge-security.md` |
| Amendments folded into the brief | done: 49 amendments F1 to F49 (4a: F3, F6 to F24; 4b: F4, F5, F25 to F47; both: F1, F2; F48 and F49 are the 4a and 4b work splits), brief 335 lines, no finding rejected; conflicts resolved to the safer option (expiry batch 50; overdue webhook treated as late payment; session-level advisory lock outside a transaction; reservation FK kept). Owner confirmed 2026-11-19 (all four as recommended; the `reservation-limit` alert reworded to "You can't reserve more of this product until your unpaid orders are paid or cancelled."): new UI texts (`reservation-limit` alert, "This payment has expired"), defaults (30 min late-payment window, 5 min fake session grace, 48 max event attempts, Hikari pool 20), F39 fallback (drop `manage-account` if Keycloak keeps `email_verified` after an email change), F32 Goal wording. Owner decisions taken 2026-11-19: D20 split 4a then 4b; D21 to D25 and D27 as recommended with every challenger finding folded in; S17 per-product cap of 10 units across a customer's unpaid orders; D26 option A (Mailpit UI on a Gateway listener at 127.0.0.1:8026, local overlay only, `cluster-up` warns if the mapping is absent; rejected: a `kubectl port-forward` target). Rationale: https://claude.ai/artifact/7VPSkVWu52ddLMxCUf5ZJj |
| Implementation (4a) | `ci-api-build-offline` merged (PR #7, `5de4ef2`). 4a started 2026-11-19 per F48 on branch `slice-4a-checkout`: phase 4a.1 API-ORDER running. DOCS drafts done: ADR 0008 (76 lines, Proposed, Deviations to fill), threat model Checkout section K1 to K17, R29 closed, R25 restated, R37, R42 to R46, R49 added (197 lines); code-dependent claims marked "(verify after implementation)". Branch fix 2026-11-19: work had started on the stale local `slice-4-checkout` (before PR #7); owner moved it to `slice-4a-checkout` from `main`. API contract final (`build/reports/slice-4a-contract-ready.md`; `Money` moved to `catalog.pricing`, schema unchanged); phase 4a.2 WEB and E2E started. E2E specs done: project `checkout` (only `checkout.spec.ts`, depends on `admin-writes`), `support/shopper.ts` `resetShopper`, `apiFetch` gained request headers and returns response headers; `make e2e-lint` passes; `make e2e` waits for API and WEB. UI hooks it assumes (label `Quantity`, `Cart` link name, `Reserved until`, exact status labels) sent to WEB. WEB done: `make web-lint web-test web-build` pass (388 tests, 100%, no new exclusions); client and route tree regenerated; `problemSchema` moved to `src/lib/problem.ts`; key kept on 5xx, network error and 413, dropped on 201, `cart-changed`, `price-changed`, 422. Deviations: client shows an overdue unpaid order as `Expired` and hides `Cancel order` (timer); extra texts for out-of-stock heading, in-progress after retry, cancel 409, add-to-cart 404 and `cart-full`; `Cart` link only for the `customer` role. All five e2e UI hooks hold. API-ORDER done: `api-lint`, `api-build` pass; `api-test` 152 unit, 430 total, 100% line and branch, no exclusions; `api-spec` twice, no diff; StockRaceIT 40 runs green in one `api-test` (4 variants x 5, crossed-order x 20), no deadlock in the Postgres log. Deviations: order and lines inserted before reserving (reservation FK to `orders`; lock order unchanged); `admin` allows `catalog::pricing` (for `Money`); Java `OrderStatus` has the three 4a values (DB CHECK all six); no `consume`/`takeLate` yet (4b); pending and per-product caps checked before the product lock; `digest` takes the algorithm (testable error branch); test-only `ReservationPeeker`, `Principals`, `Identity.emailVerified`, `shop.jobs.enabled=false` in `@IntegrationTest`. E2E done (2026-11-20): `make e2e-lint` passes; `make e2e` 92 passed twice in a row (logs `build/reports/e2e-4a-run{1,2}.log`; each run is a fresh tmpfs stack, so `resetShopper` repeatability is proven only by the two `cluster-e2e` runs). Spec fixes: order page asserts line total and total; admin out-of-stock asserted as the cart marker plus disabled `Checkout`, and the 409 through the API; exact text matches. Open before review: the order view shows no unit price (brief: snapshot price; `OrderLine.unitPrice` is in the contract), small WEB fix. Paused here at the owner's request. Resume with: WEB unit-price fix, phase 4a.3 DOCS, then integration (`make generate`, `make lint`, `make ci`, `make cluster-down cluster-up deploy cluster-e2e` and a second `cluster-e2e`, a second `make deploy`, `make dev` manual pass), then both reviews |
| Integrated `make ci` and `make cluster-up deploy cluster-e2e` | not started |
| Review (general) | not started |
| Review (security-only) | not started |
| Owner commits and opens the PR | not started |

