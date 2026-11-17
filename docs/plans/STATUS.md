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

## Next

Slice 0 and the test follow-up are committed and pushed, and the GitHub checks passed (owner confirmed 2026-11-17). The owner works through the rest of `tests/manual/README.md`. Git and GitHub operations (branches, commits, pushes, PRs) are the owner's; Claude edits the working tree only and supplies the commands.
