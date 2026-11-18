# CLAUDE.md

Repo rules for Claude and every agent it starts. They add to the owner's global instructions; where the two differ, this file wins for this repo.

## Start here

1. Read `docs/plans/STATUS.md`. It is the resume point: find the slice in progress and continue from the first row of its table that is not done.
2. Read `docs/plans/master-plan.md` and the current `slice-N-brief.md`. A brief's "Amendments" section overrides its body.
3. `docs/plans/` is the source of truth for plans. Edit it there, never in `~/.claude/plans/`.

Update `STATUS.md` at every step change (brief written, challenge done, implementation finished, review round, owner commit). Do not batch updates to the end of a slice. A decision that changes during implementation goes in the brief's amendments and in the slice's "Changes to the brief" list in `STATUS.md`.

## Process per slice

All agents are Opus except the Sonnet implementers.

1. **Brief.** An agent reads the code, checks versions against registries and official docs, and writes `docs/plans/slice-N-brief.md`.
2. **Design challenge.** One fresh challenger per slice, given only the brief and the code. For the high-risk slices (3: auth, sessions, CSRF, roles; 4: checkout, stock reservation, idempotency, payment webhooks; 6: supply chain, signing, GitOps) a second challenger runs independently with a security and failure-mode lens: threat model and trust boundaries, authorisation design, replay and concurrency, secrets handling, and what breaks or leaks when a dependency fails. Accepted findings from both are appended to the brief as amendments; the body is not rewritten.
3. **Implementation.** Sonnet agents in parallel on non-overlapping files (api, web, deploy, e2e, docs). Each verifies with real commands in containers.
4. **Integration.** The orchestrator runs `make ci`, plus `make cluster-up deploy cluster-e2e` whenever app, deploy or e2e code changes (CI runs it on every PR), and `make dev` where the slice changes the dev stack.
5. **Review.** A fresh agent reviews the diff without the orchestrator's conclusions. Fix and resubmit until it approves. Slices 3, 4 and 6 add a second, security-only reviewer on the same diff (authentication and sessions, authorisation on every endpoint, input abuse, concurrency and replay, secrets, what an attacker can reach); both must approve.
6. **Report.** Record each round's findings and resolutions in `STATUS.md`, report them to the owner, and give the git commands for the owner to run.

Why the second challenger and reviewer only on slices 3, 4 and 6 (decided 2026-11-17): agents with the same brief and lens mostly duplicate each other, while in slices 0 and 1 two reviewers with different focus each found real defects the other missed. A second agent earns its cost only with a different angle, on slices where a missed flaw is expensive.

## Rules for every agent prompt

Put these in every agent prompt; agents do not see this file's context otherwise.

- Run every tool through the `Makefile`; nothing is installed on the host. Never install system packages.
- If Docker fails with a permission error, the shell lacks the `docker` group: prefix with `sg docker -c "..."`.
- No git commands except read-only `git status` and `git diff`. The owner runs all others (add, commit, branch, switch, stash, push, and so on).
- Coverage gates are 100%: line and branch for the API; lines, branches, functions and statements for the web app. The only approved exclusions are `apps/web/src/routeTree.gen.ts` and `apps/web/src/components/ui/**`. Do not add an exclusion, ignore comment or lower threshold; stop and ask the owner.
- pnpm enforces a 7-day release-age gate (`minimumReleaseAge` in each `pnpm-workspace.yaml`); pick versions at least 7 days old rather than disabling it.
- Every image and tool version is pinned in `deploy/versions.mk` (mostly updated by Renovate). Add new pins there. Some pins are bumped by hand together, such as the `kindest/node` digest and the schema refs; read the comments in the file before changing one.
- Never hand-edit generated files: `services/api/openapi/openapi.json`, `apps/web/src/api/schema.d.ts`, `apps/web/src/routeTree.gen.ts`. Run `make generate` after changing the API contract or routes; `make lint` fails when the client or route tree is stale; `make test` fails when `openapi.json` is stale.
- Keep files under 500 lines. No working files, notes or test output in the repo root; reports go under `build/`.
- Never commit or print secrets. The dev Postgres password in compose is local-only and the only exception.

## Commands

| Need | Command |
|---|---|
| Dev stack (http://localhost:5173) | `make dev`, `make logs`, `make down` |
| The PR workflow's main job | `make ci` (`lint test build scan e2e`); a second job runs `make cluster-up deploy cluster-e2e` |
| One part | `make lint`, `make test`, `make build`, `make scan`, `make e2e E2E_ARGS=specs/catalog.spec.ts` |
| Format | `make fmt` |
| Regenerate contract, client, route tree | `make generate` |
| kind cluster (http://localhost:8088) | `make cluster-up deploy`, `make cluster-e2e`, `make cluster-down` |
| kubectl | `make kubectl ARGS="-n shop get pods"` |

`make help` lists every target. `tests/README.md` maps the test kinds; `tests/manual/README.md` holds checks done by hand.

## Known traps

Check official docs rather than memory: Spring Boot 4, Java 25, Vite 8, pnpm 12, TypeScript 7, ESLint 10, Vitest 5 and kubelet 1.36 are newer than most training data.

- Jackson 3 packages are `tools.jackson.*`. Use `RestClient`, never `RestTemplate`.
- Testcontainers 2 renamed artifacts and packages (`org.testcontainers:testcontainers-postgresql`, `org.testcontainers.postgresql.PostgreSQLContainer`).
- No Bitnami images or charts.
- Vite 8: check each Vite plugin supports it before adding or bumping one.
- `typescript` is aliased to TypeScript 6 for `typescript-eslint`; `tsc` comes from the `typescript7` alias.
- pnpm 12 reads `pnpm_config_*` environment variables, not `npm_config_*`.
- The Vite dev server does not enforce the CSP; only the built web image does, so CSP issues surface in `make e2e`.
- The local kind node runs on cgroup v1 with `failCgroupV1: false`; CI is cgroup v2.

## Where decisions live

- `docs/adr/`: why things are built this way. Add or update an ADR when a slice makes a major decision.
- `README.md`: targets, layout and known limits. Keep it in step with the code.
