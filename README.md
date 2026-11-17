# shop

Spring Boot API (`services/api`) and React web app (`apps/web`). Every tool runs in a pinned container; `make` is the only entry point, locally and in CI.

## Prerequisites

- Docker (29.x) with Compose v5
- GNU make
- Your user in the `docker` group (or equivalent access to the Docker socket)

No Java or Node is needed on the host.

## Quick start

```
make dev      # Postgres, Valkey, Mailpit, API, web dev server
make logs     # follow startup; the first API start downloads Maven dependencies and is slow
make down
```

- Web: http://localhost:5173 (proxies `/api` to the API container)
- Mailpit UI: http://localhost:8025
- Postgres: `localhost:5432`, database and user `shop`, password `dev-only-postgres-password` (local dev only)

The web page shows "API unavailable" until the API has finished starting.

## Targets

Run `make help` for the list. `make ci` is exactly what the PR workflow runs: `lint test build scan e2e`.

| Target | Purpose |
|---|---|
| `dev`, `down`, `logs` | Local stack |
| `api-stop`, `api-start` | Stop and start the API in the local stack, to see the unavailable state |
| `lint` | Spotless, ESLint, `tsc`, Prettier (API, web and `tests/e2e`), Playwright version lock, hadolint, actionlint, zizmor, `compose config` |
| `test` | `mvn verify` (unit and Testcontainers tests) and `vitest run --coverage`; both fail below 100% coverage |
| `build` | Images `shop/api:dev` and `shop/web:dev` |
| `e2e` | Playwright tests in Chromium against the built images behind a test-only gateway; reports in `build/reports/e2e`. Pass Playwright arguments with `E2E_ARGS`, e.g. `make e2e E2E_ARGS=specs/home.spec.ts` |
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
services/api/               Spring Boot API
apps/web/                   React app
tests/e2e/                  Playwright project and its Compose stack
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
- `docs/adr/`: why the tooling and testing are set up this way.

## Manual steps

Some things cannot be automated: first-time Docker group setup, checking the first run on a GitHub runner, Renovate and branch protection settings, screen-reader and non-Chromium checks. They are listed with exact steps in `tests/manual/README.md`.

## Known limits

- GitHub code scanning (SARIF upload and the CodeQL workflow) needs a public repository or GitHub Advanced Security. The workflows check `github.event.repository.visibility == 'public'`: SARIF uploads and CodeQL are skipped on private repositories, and SARIF uploads are also skipped on fork pull requests.
- CodeQL may warn about TypeScript 7 support.
- Two TypeScript versions are installed in `apps/web` and in `tests/e2e`. `typescript-eslint` needs the TypeScript 6 JS API, so the `typescript` package is aliased to `@typescript/typescript6`; `tsc` (type check and build) comes from the `typescript7` alias, which is TypeScript 7. Drop the alias once `typescript-eslint` supports TypeScript 7.
- The web runtime image runs `apk upgrade` because the pinned nginx base image lagged Alpine security fixes. Remove it when Renovate brings a base image that passes the scan without it.
- The first `make dev` is slow while Maven downloads dependencies into `.cache/m2`.
- `trivy convert` cannot filter unfixed vulnerabilities, so `--ignore-unfixed` is applied at scan time and also narrows the SARIF output.
- `make test` mounts the Docker socket into the Maven container, which is root-equivalent on the host. See `docs/adr/0001-containerised-tooling.md`.
