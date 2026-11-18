# shop

Spring Boot API (`services/api`) and React web app (`apps/web`). Visitors can browse a seeded product catalog: search, filter by category, sort, page through results and open a product page. Every tool runs in a pinned container; `make` is the only entry point, locally and in CI.

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

The web app shows an error until the API has finished starting. `make dev` runs the API with the Spring profiles `dev` and `seed`.

## Run on Kubernetes (kind)

```
make cluster-up deploy   # kind cluster, Calico, Envoy Gateway, Postgres operator, then the app
make cluster-e2e         # front-door smoke check, Playwright suite, NetworkPolicy test
make kubectl ARGS="-n shop get pods"
make cluster-down
```

Open http://localhost:8088 for the seeded catalog. Running `make deploy` again with nothing changed restarts no pods. The first run downloads about 1.5 GB of images. With everything running the cluster uses about 2.5 GB (an estimate; `docker stats shop-control-plane --no-stream`). Why it is built this way: `docs/adr/0005-local-kubernetes-on-kind.md`.

## What the app does

Web routes:

| Route | Shows |
|---|---|
| `/` | Product catalog. Search, category, sort and page live in the URL (`?q=&category=&sort=&page=`), so views can be bookmarked and shared. `page` is 1-based in the URL |
| `/products/$slug` | One product |
| `/status` | API status and version |

API endpoints (all under `/api`, errors are RFC 9457 Problem Details):

| Endpoint | Purpose |
|---|---|
| `GET /api/categories` | All categories, ordered by name |
| `GET /api/products?q=&category=&sort=&page=&size=` | One page of products. `sort` is `relevance`, `name`, `price_asc` or `price_desc`; `page` is 0-based; `size` is 1 to 48 (default 12) |
| `GET /api/products/{slug}` | One product, or 404 |
| `GET /api/status` | Status and version |

The contract is committed as `services/api/openapi/openapi.json`; the design is in `docs/adr/0003-catalog-data-and-api.md`.

### Spring profiles

| Profile | Effect |
|---|---|
| none (production defaults) | JSON logs, no demo data, `/v3/api-docs` off |
| `dev` | Readable console logs, `/v3/api-docs` on |
| `seed` | Loads the demo catalog (about 60 products, 6 categories) |

## Targets

Run `make help` for the list. `make ci` is exactly what the PR workflow runs: `lint test build scan e2e`.

| Target | Purpose |
|---|---|
| `dev`, `down`, `logs` | Local stack |
| `api-stop`, `api-start` | Stop and start the API in the local stack, to see the unavailable state |
| `lint` | Spotless, ESLint, `tsc`, Prettier (API, web and `tests/e2e`), `k8s-lint`, checks that the generated API client and route tree are up to date, Playwright version lock, hadolint, actionlint, zizmor, `compose config` |
| `test` | `mvn verify` (unit and Testcontainers tests) and `vitest run --coverage`; both fail below 100% coverage |
| `build` | Images `shop/api:dev` and `shop/web:dev` |
| `e2e` | Playwright tests in Chromium against the built images behind a test-only gateway; reports in `build/reports/e2e`. Pass Playwright arguments with `E2E_ARGS`, e.g. `make e2e E2E_ARGS=specs/catalog.spec.ts` |
| `cluster-up`, `cluster-down` | Create the kind cluster `shop` with its add-ons (idempotent), and remove it with its kubeconfig |
| `deploy` | Build, load `shop/api:dev` and `shop/web:dev` into the cluster and apply `deploy/k8s/overlays/local` |
| `cluster-e2e` | The Playwright suite against Envoy in the cluster, plus the NetworkPolicy test; reports in `build/reports/cluster-e2e`. `E2E_ARGS` works as for `e2e` |
| `cluster-smoke`, `cluster-diagnostics`, `cluster-traffic` | Check `127.0.0.1:8088`, collect pod state and logs, and send steady traffic (used to watch a rollout) |
| `kubectl` | Run kubectl in the tools container: `make kubectl ARGS="-n shop get pods"` |
| `k8s-lint`, `k8s-tools` | Render and validate the manifests with kubeconform (part of `lint`); build the kind, Helm and kubectl tools image |
| `generate` | Regenerate everything derived from code, in order: `api-spec`, `web-api-client`, then the route tree. Run it after changing the API contract or routes, and commit the results |
| `api-spec` | Rewrite `services/api/openapi/openapi.json` by running only `OpenApiContractIT` (needs the Docker socket like `make test`) |
| `web-api-client` | Regenerate `apps/web/src/api/schema.d.ts` from the committed OpenAPI document |
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
services/api/openapi/       committed OpenAPI document (generated, see make generate)
apps/web/                   React app
deploy/k8s/                 Kustomize base and local overlay for the app
deploy/kind/                kind cluster config, Calico, Helm values, GatewayClass
deploy/k8s-tools/           Dockerfile for the kind, Helm and kubectl tools image
tests/e2e/                  Playwright project and its Compose stack
tests/e2e/cluster/          NetworkPolicy test and its probe pods
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
- `docs/adr/`: why the tooling, testing, catalog API, web security headers and local Kubernetes cluster are set up this way.

## Manual steps

Some things cannot be automated: first-time Docker group setup, checking the first run on a GitHub runner, Renovate and branch protection settings, screen-reader and non-Chromium checks. They are listed with exact steps in `tests/manual/README.md`.

## Known limits

- Kubernetes (kind): on a cgroup v1 Docker Desktop (this laptop's) the cluster sets `failCgroupV1: false` for kubelet 1.36, which is a stopgap; CI is cgroup v2. `make cluster-up` reapplies a Helm release only when its chart version or values change. No TLS or HSTS on localhost; one node and one Postgres instance, with data lost on `make cluster-down`; `make deploy` uses local `:dev` images (registry and digests come in slice 6); after a Docker Desktop restart the cluster may not recover, so run `make cluster-down cluster-up deploy`; port 8088 must be free; `make cluster-e2e` reaches the cluster through the node hostname with a browser host mapping, not through your host's localhost.
- GitHub code scanning (SARIF upload and the CodeQL workflow) needs a public repository or GitHub Advanced Security. The workflows check `github.event.repository.visibility == 'public'`: SARIF uploads and CodeQL are skipped on private repositories, and SARIF uploads are also skipped on fork pull requests.
- CodeQL may warn about TypeScript 7 support.
- Two TypeScript versions are installed in `apps/web` and in `tests/e2e`. `typescript-eslint` needs the TypeScript 6 JS API, so the `typescript` package is aliased to `@typescript/typescript6`; `tsc` (type check and build) comes from the `typescript7` alias, which is TypeScript 7. Drop the alias once `typescript-eslint` supports TypeScript 7.
- The web runtime image runs `apk upgrade` because the pinned nginx base image lagged Alpine security fixes. Remove it when Renovate brings a base image that passes the scan without it.
- Catalog search matches whole words only: no prefixes and no typo tolerance (`docs/adr/0003-catalog-data-and-api.md`).
- Products have no images yet; the web app shows a placeholder.
- The Vite dev server does not enforce the Content Security Policy; only the built web image does (`docs/adr/0004-web-security-headers.md`).
- The API sends only a few security headers until Spring Security arrives in slice 3, and HSTS waits for the TLS-terminating Gateway.
- The first `make dev` is slow while Maven downloads dependencies into `.cache/m2`.
- `trivy convert` cannot filter unfixed vulnerabilities, so `--ignore-unfixed` is applied at scan time and also narrows the SARIF output.
- `make test` mounts the Docker socket into the Maven container, which is root-equivalent on the host. See `docs/adr/0001-containerised-tooling.md`. The kind container in `cluster-up`, `deploy` and `cluster-diagnostics` gets it too (`docs/adr/0005-local-kubernetes-on-kind.md`); kubectl and Helm do not.
