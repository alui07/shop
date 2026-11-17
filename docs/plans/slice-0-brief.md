# Slice 0 implementation brief: tooling, Makefile, compose dev, PR pipeline

Repo: `/home/alui/code/shop` (empty git repo, branch `main`, no commits). Overall plan: `/home/alui/.claude/plans/splendid-noodling-dewdrop.md`.

## Goal

A runnable skeleton where every tool runs in a container and one Makefile is the entry point, locally and in CI. No product features. Done means:

- `make dev` starts Postgres, Valkey, Mailpit, the API and the web dev server; `http://localhost:5173` shows a page that displays the API status fetched through same-origin `/api`.
- `make ci` (lint, test, build, scan) passes locally and is exactly what the PR workflow runs.

## Hard rules

- Nothing is installed on the host. The host has only `make`, `docker` (29.x) and `docker compose` (v5). No host Java, no reliance on host Node.
- Never run git commands that change the index or history (no add, commit, stash, etc.).
- No secrets in the repo. Dev-only credentials for local Postgres live in `deploy/compose/.env.example`-style defaults clearly marked dev-only; no `.env` committed.
- Code is self-explanatory; comments only for non-obvious workarounds. Files under 500 lines. Nothing in the repo root except `Makefile`, `README.md`, `.gitignore`, `.dockerignore`-type config, `renovate.json`, `.editorconfig`.
- In this session the shell lacks the `docker` group: run docker and make as `sg docker -c "make <target>"`. The Makefile itself calls plain `docker`.
- No Bitnami images. No `RestTemplate`. Jackson 3 packages (`tools.jackson.*`) if Jackson is touched. Testcontainers 2 artifact and package names (`org.testcontainers:testcontainers-postgresql`, `org.testcontainers.postgresql.PostgreSQLContainer`), check the 2.0 docs rather than memory.
- Versions below were read from registries on 2026-11-16. Check official docs when an API differs from memory (Spring Boot 4.1, Vite 8, pnpm 12, TypeScript 7, ESLint 10, Vitest 5 are all newer than most training data).

## Pinned versions

| Thing | Version |
|---|---|
| Spring Boot | 4.1.2 (parent POM) |
| Java | 25 (`maven:3.9.16-eclipse-temurin-25` build, `eclipse-temurin:25.0.5_8-jre-alpine-3.24` runtime) |
| Testcontainers | 2.0.5 (use the version managed by Boot if it manages one) |
| Spotless Maven plugin | 3.11.0, palantir-java-format |
| Node | 26.13.1 (`node:26.13.1-alpine3.24`) |
| pnpm | 12.21.0 |
| Vite 8.3.8, React 19.3.1, TypeScript 7.0.2, Vitest 5.1.0, ESLint 10.15.0, `@vitejs/plugin-react` 6.3.0 | exact versions in `package.json` via lockfile |
| Postgres | `postgres:18.7-alpine3.24` |
| Valkey | latest stable 9.x alpine (not an rc; look it up on Docker Hub) |
| Mailpit | `axllent/mailpit:v1.33.1` |
| nginx | `nginxinc/nginx-unprivileged` current stable alpine-slim, exact version tag |
| Trivy | `aquasec/trivy:0.76.2` |
| hadolint | `hadolint/hadolint:v2.15.1-alpine` |
| actionlint | `rhysd/actionlint:1.7.12` |
| actions/checkout | v7.0.1 `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| github/codeql-action | v4.39.3 `33d7b85da1a04171e211befe2c739303b9a4c066` |

All images are pinned `tag@sha256:digest` (resolve digests with `docker buildx imagetools inspect`). GitHub Actions are pinned by full commit SHA with a version comment. Renovate keeps both current.

## Design decisions

1. **Maven, not Gradle.** Familiar to the owner, first-class Renovate and CodeQL support. No wrapper: Maven only ever runs inside the pinned image.
2. **pnpm, not npm.** Lifecycle scripts blocked by default and a minimum release age setting (set to 7 days in `pnpm-workspace.yaml` or wherever pnpm 12 reads it); both are supply-chain controls worth showing. Node 26 no longer ships corepack, so the dev image installs `pnpm@12.21.0` with `npm install -g`.
3. **Trivy runs from its pinned container image through `make scan`, not `aquasecurity/trivy-action`.** The action's tags were force-pushed in the March 2026 compromise (GHSA-69fq-xp46-6x23); running the digest-pinned image removes that dependency and keeps local and CI identical. CI needs only two third-party actions: `actions/checkout` and `github/codeql-action`.
4. **CI calls `make ci`.** No setup-java or setup-node. The pipeline is reproducible locally by construction.
5. **Tool containers run as the host UID/GID** (`--user $(id -u):$(id -g)`), with `HOME` pointed at a named cache volume (`shop-m2`, `shop-pnpm-store`), so files written into the bind-mounted repo are owned by the developer. Caches volumes must be created/chowned so a non-root user can write them (init step in the Makefile, idempotent).
6. **Testcontainers runs inside the Maven container against the host daemon**: mount `/var/run/docker.sock`, add `--group-add <gid of the socket as seen inside a container>` (verified here: socket is `0:1001 660` inside containers; compute the gid in the Makefile with `stat` on the host socket, falling back sensibly on CI where it differs), and make sibling containers reachable (`--add-host=host.docker.internal:host-gateway` plus `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`, or `--network host`; pick whichever works both on Docker Desktop/WSL and on `ubuntu-24.04` GitHub runners, and say which). Ryuk stays enabled if it works; if it must be disabled, say why. Trade-off recorded in an ADR: socket access makes the build container root-equivalent on the host; accepted for local dev and ephemeral CI runners.
7. **Single origin in dev**: Vite dev server on 5173 proxies `/api` to the API container. Only 5173 (web), 8025 (Mailpit UI) and 5432 (Postgres, for a DB client) are published, all bound to `127.0.0.1`.
8. **Slice 0 API surface**: `GET /api/status` returning `{"status":"ok","version":"<build version>"}`, plus Actuator health with separate liveness and readiness groups on the management endpoints. The app connects to Postgres and runs one Flyway baseline migration (`V1__baseline.sql`, creating nothing more than is needed to prove the wiring, e.g. an empty schema comment or a `schema_info` table) so the compose wiring and the Testcontainers path are both proven now rather than in slice 1. Spring Modulith, security, sessions, Valkey client: not yet (Valkey and Mailpit containers run but nothing connects).
9. **Lint set**: Spotless check (Java), ESLint + `tsc --noEmit` (web), hadolint (Dockerfiles), actionlint (workflows), `docker compose config -q`.
10. **Scan set**: `trivy fs` (vulnerabilities, secrets, misconfig) over the repo and `trivy image` over both built images. Fail on HIGH and CRITICAL with `--ignore-unfixed`; a `.trivyignore` is allowed only with a dated justification per entry. SARIF output is uploaded to GitHub code scanning in CI.

## Layout to create

```
Makefile
README.md                     quick start, target list, prerequisites, known limits
.gitignore  .dockerignore  .editorconfig  renovate.json
services/api/
  pom.xml
  Dockerfile                  stages: build (maven) -> runtime (temurin jre, non-root, layered jar)
  src/main/java/dev/shop/api/ApiApplication.java
  src/main/java/dev/shop/api/status/StatusController.java
  src/main/resources/application.yaml
  src/main/resources/db/migration/V1__baseline.sql
  src/test/java/...           StatusControllerTest (slice test), ApplicationIT (Testcontainers Postgres: context loads, Flyway applied, /api/status and readiness return 200)
apps/web/
  package.json  pnpm-lock.yaml  tsconfig*.json  vite.config.ts  eslint.config.js  index.html
  Dockerfile                  stages: dev (node+pnpm) -> build -> runtime (nginx-unprivileged serving dist, SPA fallback)
  nginx.conf
  src/main.tsx  src/App.tsx  src/api/status.ts  src/App.test.tsx
deploy/compose/compose.yaml   postgres, valkey, mailpit, api (dev), web (dev); healthchecks; depends_on with conditions
docs/adr/0001-containerised-tooling.md   one page: Makefile + containers, socket trade-off, Trivy-image-not-action
.github/workflows/ci.yaml     pull_request + push to main; job `ci` runs `make ci`, uploads SARIF
.github/workflows/codeql.yaml java-kotlin, javascript-typescript, actions; PR + weekly schedule
```

Base package `dev.shop.api`. Maven coordinates `dev.shop:api`.

## Makefile contract

`help` (default, self-documenting), `dev`, `down`, `logs`, `lint`, `test`, `build`, `scan`, `ci` (= lint test build scan), `fmt` (apply Spotless and ESLint fixes), `clean`. Sub-targets `api-*` and `web-*` where it helps. Requirements:

- `.DEFAULT_GOAL := help`, `SHELL := bash`, `.SHELLFLAGS := -euo pipefail -c`, all targets `.PHONY`.
- Image references defined once (variables at the top or an included `deploy/versions.mk`) so Renovate has one place to update; the same refs are passed to Dockerfiles and compose as build args / env, not duplicated.
- `make test` runs unit tests and integration tests for both apps. API: `mvn verify` (Surefire unit, Failsafe `*IT`). Web: `vitest run`.
- `make build` builds `shop/api:dev` and `shop/web:dev` runtime images.
- `make dev` uses compose with source bind mounts: API runs `mvn spring-boot:run` (devtools restart is optional; do not spend time on it), web runs `vite --host`.

## Runtime image requirements

- API: non-root numeric user, layered jar extraction (Boot 4 `jarmode=tools` extract), `exec` form entrypoint, `HEALTHCHECK` not required (Kubernetes probes come in slice 2), no build tools in the final image.
- Web: nginx-unprivileged on 8080, SPA fallback, `/api` not proxied by this image (the Gateway does that from slice 2). Security headers and CSP come in slice 1; do not add a half-finished CSP now.
- Both pass hadolint and `trivy image` at the threshold above.

## CI workflow requirements

- Top-level `permissions: contents: read`; jobs add only what they need (`security-events: write` for SARIF upload and CodeQL).
- `concurrency` cancels superseded PR runs. `timeout-minutes` on every job. `runs-on: ubuntu-24.04`.
- `actions/checkout` with `persist-credentials: false`.
- SARIF upload runs with `if: always()` so findings appear even when the scan fails the job. Note that code scanning upload needs a public repo or GHAS; the workflow must not fail on a fork PR without the permission (guard the upload step).
- CodeQL for Java uses `build-mode: none` unless the docs for v4.39 say Java 25 needs a build; state which was chosen and why.
- Renovate: `renovate.json` extending `config:recommended`, with digest pinning for Docker and Actions, a `minimumReleaseAge` of 7 days, grouped non-major updates, and custom regex managers for image refs in the Makefile/`versions.mk`. Renovate runs as the hosted GitHub app (owner installs it); no self-hosted workflow.

## Work split (parallel, no overlapping files)

- **Agent API**: everything under `services/api/`.
- **Agent WEB**: everything under `apps/web/`.
- **Agent PLATFORM**: `Makefile` (and `deploy/versions.mk` if used), `deploy/compose/`, `.github/`, `renovate.json`, root dotfiles, `README.md`, `docs/adr/`.

Interface between them (fixed, so agents do not need to coordinate):

- API Dockerfile: context `services/api`, build args `MAVEN_IMAGE`, `JRE_IMAGE`; final stage is the default target. Listens on 8080; management on 8081 (`/actuator/health/liveness`, `/actuator/health/readiness`). Config by env: `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`.
- Web Dockerfile: context `apps/web`, build args `NODE_IMAGE`, `NGINX_IMAGE`, `PNPM_VERSION`; stage named `dev` (workdir `/app`, pnpm available, usable as arbitrary UID with `HOME` overridden); final stage is the default target, listens on 8080. Vite proxy target from env `API_PROXY_TARGET` (default `http://localhost:8080`). Scripts in `package.json`: `dev`, `build`, `lint`, `typecheck`, `test`, `format`.
- Maven commands the Makefile will run in the Maven image with the repo's `services/api` mounted at `/workspace`: `mvn -B -ntp spotless:check`, `mvn -B -ntp verify`, `mvn -B -ntp spotless:apply`, `mvn spring-boot:run`.

Each agent verifies its own part by running the real commands in containers (via `sg docker -c "docker run ..."`) and reports the exact commands and results, plus anything it could not verify. The orchestrator runs the integrated `make ci` and `make dev` afterwards.

## Out of scope

Catalog, auth, Modulith, Valkey usage, Playwright (slice 1 brings the first flow worth testing), Kubernetes, observability, image publishing and signing.

---

# Amendments after the design challenge (these OVERRIDE anything above that conflicts)

## Versions
- A1. npm package versions in the table are a ceiling. pnpm's 7-day `minimumReleaseAge` applies to every direct and transitive dependency, so pick the newest version of each package that is at least 7 days old. Do not add per-package exclusions to get past the gate.

## Caches and users (replaces design decision 5)
- A2. No named cache volumes. Caches are bind-mounted, gitignored folders under the repo: `.cache/m2`, `.cache/pnpm`, `.cache/trivy`, `.cache/home`. The Makefile creates them with `mkdir -p` as the host user. No chown step.
- A3. The Makefile exports `HOST_UID` and `HOST_GID` (`$(shell id -u)`, `$(shell id -g)`). Every tool container and the compose `api` and `web` services run as `${HOST_UID}:${HOST_GID}` with `HOME=/cache/home` (the `.cache/home` mount). Compose uses `user: "${HOST_UID:?set by make}:${HOST_GID:?set by make}"`.
- A4. Maven: the repo's `.cache/m2` is mounted at `/cache/m2`; run with `--entrypoint mvn` (the image's default entrypoint copies into an unwritable `MAVEN_CONFIG`) and `-Dmaven.repo.local=/cache/m2/repository` (via `MAVEN_ARGS` or on the command line).
- A5. pnpm: store at `/cache/pnpm` (`.cache/pnpm`), set explicitly via `store-dir` (env `npm_config_store_dir` or the pnpm 12 equivalent; check docs), and `package-import-method=copy` because store and repo are different mounts. `node_modules` lives in the bind-mounted `apps/web/node_modules`, owned by the host user; no anonymous volumes.

## Testcontainers (replaces design decision 6)
- A6. Socket GID is probed from inside a container, lazily (recursive `=` so `make help` starts nothing):
  `DOCKER_SOCK_GID = $(shell docker run --rm -v /var/run/docker.sock:/s $(ALPINE_IMAGE) stat -c %g /s)`
- A7. Networking: mount the socket and add the group; set NO host override and NO `--network host`. Testcontainers detects it is in a container and reaches siblings through the default gateway. Ryuk stays enabled. If this does not work on this machine, report exactly what failed before trying alternatives, then use the least invasive working option and document it.
- A8. The socket is mounted only for the API test target (`mvn verify`). Not for lint, fmt, `spring-boot:run`, web targets, or Trivy.
- A9. `ApplicationIT` reads the Postgres image from env `POSTGRES_IMAGE` (the Makefile passes it); no hard-coded image ref in the test. Testcontainers needs `asCompatibleSubstituteFor("postgres")` when the ref carries a digest or non-default name; check.

## Spring Boot 4 traps (API agent: verify each against the Boot 4.1 docs)
- A10. `spring-boot-starter-webmvc` (not `-web`). Flyway needs `spring-boot-starter-flyway` plus `flyway-database-postgresql`. `@WebMvcTest` needs `spring-boot-starter-webmvc-test` and lives in a new package. `@ServiceConnection` needs `spring-boot-testcontainers`. Do NOT add `spring-boot-docker-compose`.
- A11. `version` comes from `BuildProperties` (bind the `build-info` goal), read through `ObjectProvider` so slice tests pass without it.
- A12. Dockerfile build stage uses `RUN --mount=type=cache` for the Maven repo and `-DskipTests` (tests run in `make test`, not during image build).

## Per-context ignore files and Dockerfile users
- A13. No root `.dockerignore`. API agent adds `services/api/.dockerignore` (`target/`, `.env*`); WEB agent adds `apps/web/.dockerignore` (`node_modules/`, `dist/`, `.env*`).
- A14. Both Dockerfiles end with an explicit numeric `USER` in the final stage (web: `USER 101`), otherwise Trivy DS002 fails the gate. `FROM ${ARG}` without defaults is intended (single source of image refs); do not add defaults.

## Web interface (replaces the looser wording above)
- A15. The Makefile builds the web Dockerfile `--target dev` and tags it `shop/web-tools:dev`; all web commands run in that image with `apps/web` mounted at `/app`. Commands: `pnpm install --frozen-lockfile` (target `web-deps`, a prerequisite of web lint/test/dev), `pnpm run lint`, `pnpm run typecheck`, `pnpm run test`, `pnpm run format`, `pnpm run format:check`, `pnpm run dev`.
- A16. `test` script is `vitest run`. Formatting is Prettier: `format` = `prettier --write .`, `format:check` = `prettier --check .`; `lint` = ESLint only. The Makefile's `lint` runs lint, typecheck and format:check; `fmt` runs `format`.
- A17. The page must render usefully when the API is down ("API unavailable", with retry), because on first `make dev` the API spends minutes downloading dependencies. Compose `web` depends on `api` with `service_started` only.
- A18. pnpm is installed in the dev stage with `npm install -g pnpm@${PNPM_VERSION}`; the ADR notes this bootstrap step is outside pnpm's own release-age control.

## Compose
- A19. Postgres 18 images moved the data dir: mount the named volume at `/var/lib/postgresql` (verify against the image docs).
- A20. Image refs in compose are `${POSTGRES_IMAGE:?set by make}`-style; `make lint` exports them before `docker compose config -q`.

## Versions file and Renovate
- A21. `deploy/versions.mk` is the single source. One strict format, e.g.
  `# renovate: datasource=docker depName=postgres` then `POSTGRES_IMAGE := postgres:18.7-alpine3.24@sha256:...`; pnpm uses `datasource=npm`. The regex manager captures `currentValue` and `currentDigest`.
- A22. Provide `make renovate-validate` (pinned Renovate image running `renovate-config-validator`); not part of `ci` because of image size. Run it once and report.

## Scanning (replaces design decision 10 details)
- A23. Per target (fs, api image, web image): run Trivy once with `--format json`, exit code 0, output under `build/reports/trivy/`. Then `trivy convert` twice: to SARIF (all severities) and to a table gate with `--severity HIGH,CRITICAL --ignore-unfixed --exit-code 1`. Verify `trivy convert` supports these flags in 0.76; if `--ignore-unfixed` is scan-time only, apply it at scan time and say so.
- A24. Trivy gets images without the socket: `docker save` to a tar, then `trivy image --input`. Trivy DB cache in `.cache/trivy`. `trivy fs` skips `.cache`, `node_modules`, `target`, `dist`, `build`.
- A25. CI uploads each SARIF separately with its own `category` (`trivy-fs`, `trivy-api`, `trivy-web`), each guarded with `if: always() && hashFiles('<file>') != ''` plus the code-scanning guard below.

## CI
- A26. Add `actions/cache` (SHA below) on `.cache/`, keyed on `services/api/pom.xml`, `apps/web/pnpm-lock.yaml` and `deploy/versions.mk`.
- A27. Code scanning needs a public repo (or GHAS). SARIF upload steps and the CodeQL workflow jobs are skipped when `github.event.repository.private` is true, and uploads are skipped for fork PRs (`github.event_name != 'pull_request' || github.event.pull_request.head.repo.full_name == github.repository`). README says so.
- A28. CodeQL Java uses `build-mode: none` (no compilation, so JDK 25 is irrelevant). README known limits: possible CodeQL warning about TypeScript 7.
- A29. Add `zizmor` (pinned image, look up the current tag) to `make lint` for workflow security, alongside actionlint.
