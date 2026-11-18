# Ecommerce portfolio project: plan

## Context

Goal: a portfolio project that moves a 4-year Java developer toward full-stack and platform skills: React and TypeScript, modern architecture, Kubernetes, load testing, CI/CD and security.

Decisions made by the owner:

- Product: an ecommerce website.
- Stack: React SPA (TypeScript, shadcn/ui, TanStack Router + Query) with a Java Spring Boot API.
- Work mode: Claude builds in small vertical slices; the owner reads, runs and questions each slice.
- Infrastructure: all local (Kubernetes included), plus a guide for taking it to a cloud provider. Strong interest in CI/CD.
- Container runtime: Docker Desktop with WSL integration (owner installs).
- Repo location: moves off the Windows drive into the WSL home directory.

The design was challenged by an independent Opus agent. Its accepted findings are folded in below and summarised at the end.

## Prerequisites (owner, before slice 0)

1. Install Docker Desktop and enable WSL integration for this distro; confirm `docker run hello-world` works in WSL.
2. Raise the WSL memory limit. The default is half of host RAM (about 7.5 GB), and the full stack needs 8 to 10 GB. In `C:\Users\alui0\.wslconfig`:
   ```
   [wsl2]
   memory=12GB
   swap=4GB
   ```
   then `wsl --shutdown` from Windows.
3. Create the new repo and start Claude Code there:
   ```
   mkdir -p ~/code/shop && cd ~/code/shop && git init -b main
   ```
4. For slice 6: a public GitHub repo (free GHCR and artifact attestations).

## Architecture

```
apps/web/            React SPA
services/api/        Spring Boot modular monolith
deploy/compose/      local dev dependencies
deploy/k8s/          Kustomize base + overlays (local, cloud)
loadtest/            k6 scenarios
docs/                ADRs, threat model, cloud guide
.github/workflows/   CI/CD
Makefile             single entry point; every tool runs in a container
```

| Area | Choice |
|---|---|
| Front end | Vite, React 19, TypeScript strict, TanStack Router (file-based) + Query + Form, Zod, shadcn/ui on Tailwind v4. API client generated from the backend OpenAPI spec, with a CI check that fails when it is stale. Vitest, Testing Library, Playwright with axe checks. Served by unprivileged nginx, which sets the CSP. |
| Back end | One Spring Boot 4.x service (latest GA at start) on Java 25, virtual threads, Jackson 3, `RestClient`. Modular monolith verified by Spring Modulith: catalog, cart, inventory, order, payment, customer. Errors as RFC 9457 Problem Details. |
| Data | PostgreSQL as the single source of truth: catalog, carts, stock, orders. Flyway. Full-text search with a GIN index. Money as integer minor units plus currency. |
| Valkey | Sessions and rate-limit counters (Bucket4j) only. No catalog cache up front: measure with k6, add the cache, measure again. |
| Stock | `on_hand` and `reserved` on the stock row; reserve with one conditional `UPDATE` and check the row count. `reservation` table with expiry, released in `FOR UPDATE SKIP LOCKED` batches so replicas never double-release. |
| Orders | Explicit state machine, including payment arriving after the reservation expired (re-reserve or refund). Server recomputes prices and snapshots them on order lines. Idempotency keys scoped per user, with request hash, stored response, in-progress handling and expiry. |
| Payment | Fake provider behind a port. Webhook signed with HMAC-SHA256 over body plus timestamp, replay window, constant-time compare, dedupe by event ID, own stateless filter chain. |
| Events | Spring Modulith event publication registry as the transactional outbox. Confirmation email to Mailpit. Kafka (Apache, KRaft) externalisation as an optional profile and stretch goal. |
| Auth | Keycloak (official image, realm import). Session-based OIDC client inside the API (BFF pattern): Authorization Code + PKCE, tokens server-side, `__Host-` session cookie (Secure, HttpOnly, SameSite=Lax), SPA CSRF setup from the Spring Security docs, session rotation on login, RP-initiated and back-channel logout, idle and absolute timeouts. Roles customer and admin, `@PreAuthorize` on admin endpoints, audit log of admin changes. |
| Routing | Single origin everywhere, so no CORS: `/` to nginx, `/api`, `/oauth2`, `/login` to the API. Vite proxy in dev, one Gateway hostname in the cluster. |
| Kubernetes | kind, Envoy Gateway (Gateway API), metrics-server, HPA, PDBs, distinct readiness and liveness probes, graceful shutdown, requests and limits, restricted Pod Security, NetworkPolicies with a test that a denied connection fails. Kustomize for the app; Helm only to install third-party charts. Postgres via CloudNativePG. No Bitnami images or charts. |
| Observability | OpenTelemetry. Compose: `grafana/otel-lgtm` all-in-one. Cluster: Prometheus, Grafana, Tempo, Loki under an optional `obs` profile. Dashboard with rate, errors, latency percentiles and connection-pool usage. |
| Load testing | k6: catalog browsing, full checkout, flash sale racing for limited stock, with thresholds and a no-oversell assertion. Auth through a session-minting endpoint that exists only under a `loadtest` profile, with a test proving it is absent otherwise. Framed as proof of mechanisms and bottleneck finding, not capacity numbers (one host shares the CPU). |
| CI | GitHub Actions on every PR from slice 0: lint, type check, unit and integration tests (Testcontainers, including an N-thread stock race), build, Trivy, CodeQL, Renovate. |
| CD | Main: build and push to GHCR with provenance, SBOM and keyless cosign signatures; `cosign verify`; then an automated PR bumping the image digest in `deploy/k8s/overlays/local`. Argo CD in kind (optional profile) syncs on merge: a real GitOps loop at zero cost. Flyway expand-and-contract migrations and a rollback procedure documented. |
| Docs | One-page ADR per major decision, a short threat model (auth, checkout, webhook), README with architecture diagram and known limits, cloud guide (managed Postgres, cache and identity; External Secrets; backups). |

## Build order

Each slice ends runnable and demonstrable.

0. Containerised tooling, Makefile, compose dev environment, PR pipeline with scanning.
1. Catalog browsing end to end: API, database, web, seed data, pagination, search, enforced CSP and security headers, JSON logs with trace IDs, probes, generated API client.
2. Deploy slice 1 to kind: Kustomize base and local overlay, Gateway, `make cluster-up deploy`. Later slices keep manifests current.
3. Auth: Keycloak, BFF session, CSRF, roles, admin product and stock management.
4. Cart, checkout, stock reservation, orders, fake payment, outbox and email.
5. Observability stack, k6 scenarios, HPA and PDBs under load, cache before-and-after measurement.
6. Supply chain (signing, SBOM, attestations, verification), Argo CD GitOps loop, cloud guide.

### Branch names

Create each branch from an up-to-date `main` when its slice starts, not ahead of time: `git switch main && git pull && git switch -c <branch>`.

| Slice | Branch |
|---|---|
| 1 | `slice-1-catalog` (merged, PR #1) |
| 2 | `slice-2-kind-deploy` |
| Repo `CLAUDE.md` (before slice 3) | `docs-repo-claude-md` |
| 3 | `slice-3-auth` |
| 4 | `slice-4-checkout` |
| 5 | `slice-5-load-and-observability` |
| 6 | `slice-6-supply-chain-gitops` |

## Process per slice (global instructions)

1. Opus writes the implementation brief; a separate Opus agent challenges it. Each slice gets its own fresh challenger. High-risk slices (3, 4 and 6) get a second challenger with a different lens; see below.
2. Sonnet agents implement, in parallel where files do not overlap (api, web, deploy and docs).
3. One Opus agent reviews the diff as a fresh agent; fix and resubmit until it approves.
4. Report what the reviewer raised and how it was resolved. The owner runs all git commands.

Slices 0 and 1 used two reviewers (Fable and Opus). Updated on 2026-11-17 to match the owner's global instructions.

### Repo-specific `CLAUDE.md` (to create before slice 3)

The global instructions call for one challenger and one reviewer. This repo adds rules of its own, in a `CLAUDE.md` at the repo root, written and committed before slice 3 starts:

- Design challenge: one fresh Opus challenger per slice, with no context beyond the brief and the code. For the high-risk slices (3, 4 and 6, as listed in the next bullet), a second Opus challenger runs independently with a security and failure-mode lens: the threat model and trust boundaries, authorisation design, replay and concurrency, secrets handling, and what breaks or leaks when a dependency fails. Findings from both are folded into the brief as amendments.
- Why (decided 2026-11-17): challengers given the same brief and the same lens mostly duplicate each other's findings. A second challenger is worth adding only if it looks from a different angle, and only on slices where a design flaw is expensive. The other slices keep one challenger.
- For security-sensitive slices (3: auth, sessions, CSRF, roles; 4: checkout, stock reservation, idempotency, payment webhooks; 6: supply chain, signing, GitOps), a second Opus reviewer runs alongside the first with a security-only brief: authentication and session handling, authorisation on every endpoint, input abuse, concurrency and replay, secrets, and what an attacker can reach. It reviews the same diff independently; both must approve.
- Why: in slices 0 and 1, two reviewers with different perspectives each found real defects the other missed (for example the NUL-byte 500 and a test that passed by accident), while identical reviewers would mostly duplicate each other. A second reviewer with a different focus keeps that benefit where a missed defect costs most.
- The same file can also carry the repo's standing rules that agents keep needing in their prompts: run everything through the Makefile, `sg docker` when the shell lacks the group, no git commands except read-only `git status` and `git diff`, the pnpm 7-day release-age gate, the 100% coverage gates and that only the owner approves exclusions, and `docs/plans/STATUS.md` as the resume point.

Briefs will state the traps the challenger flagged: Jackson 3 package names, no `RestTemplate`, no Bitnami, Testcontainers 2 package renames, Vite 8 plugin compatibility. Versions are checked against official docs at the start of each slice.

## Verification

- Every slice: `make test` (unit, integration, module-boundary test, all in containers), `make dev` and exercise the feature in the browser, a Playwright flow for the slice, green PR pipeline.
- From slice 2: `make cluster-up deploy` and the same Playwright flow against the cluster hostname.
- Slice 4: the concurrent stock test passes; duplicate idempotency keys and replayed or forged webhooks are rejected.
- Slice 5: `make loadtest` passes thresholds; the flash sale shows the HPA adding pods and zero oversold stock.
- Slice 6: `cosign verify` passes on the pushed image; merging the digest PR makes Argo CD roll out the new version.

## What the design challenge changed

| Raised | Resolution |
|---|---|
| Kafka inside a monolith is unjustified; Redpanda licence and memory | Modulith outbox by default; Apache Kafka optional; stock projection dropped |
| Stock, idempotency, webhook and late-payment rules underspecified | Fully specified above |
| Footprint exceeds the default WSL memory limit | `.wslconfig` prerequisite; broker, Argo CD and cluster observability are optional profiles |
| Carts in Redis split the checkout transaction | Carts in Postgres; Valkey for sessions and rate limits only |
| Same-origin routing, CSRF, logout and CSP ownership undefined | Defined above; CSP enforced from slice 1 |
| k6 cannot perform the browser login flow | Profile-gated session-minting endpoint |
| Bitnami deprecated; Gateway implementation, metrics-server, NetworkPolicy enforcement unnamed | Named above |
| Kubernetes arriving in slice 5 is a big-bang risk | Moved to slice 2; scanning moved to slice 0 |
| CD was signing without verifying, with a bot committing to main | Verify step, digest-bump PR, Argo CD in kind |
| Keycloak vs Spring Authorization Server; BFF placement; Helm vs Kustomize | Keycloak; BFF inside the API; Kustomize for the app, Helm for third-party charts |
