# ADR 0001: Containerised tooling behind one Makefile

Status: accepted, 2026-11-16

## Context

The host has only `make` and Docker. Local runs and CI should behave the same, and build tools should be pinned and updatable in one place.

## Decisions

**Makefile plus containers is the only toolchain.** Maven, Node, pnpm, linters and scanners run from pinned images (`tag@sha256:digest`) listed in `deploy/versions.mk`; Renovate updates them. CI has no setup-java or setup-node and runs `make ci`. Tool containers run as the host UID/GID so files in the repo belong to the developer; the only exception is the Renovate config validator, which reads one read-only file.

**Caches are bind-mounted folders** (`.cache/m2`, `.cache/pnpm`, `.cache/trivy`, `.cache/home`), created by the Makefile as the host user. This avoids named volumes with root ownership and lets CI cache them by path. The API image is not built from source inside `docker build`: `make api-build` packages the jar in the Maven container against the cached `.cache/m2`, and the Dockerfile only copies the extracted layers, so an image build never downloads from Maven Central (two CI failures on 2026-11-19 came from that download).

**Docker socket trade-off.** Testcontainers needs the host Docker daemon, so the API test target mounts `/var/run/docker.sock` and adds the socket's group, probed from inside a container. Socket access is root-equivalent on the host. We accept this for local development and ephemeral CI runners, and restrict it to the `mvn verify` target and the kind container in the cluster targets (ADR 0005): lint, format, run, web and scan targets never get the socket. On Docker Desktop (WSL) Testcontainers could not reach Ryuk through the default gateway, so the target also sets `--add-host=host.docker.internal:host-gateway` and `TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`. Ryuk stays enabled and host networking is not used. The same settings pass on GitHub `ubuntu-24.04` runners (first CI run, 2026-11-17).

**Trivy runs from its pinned image, not the GitHub Action.** The `aquasecurity/trivy-action` tags were force-pushed during the March 2026 compromise (GHSA-69fq-xp46-6x23). Running the digest-pinned image removes that dependency and keeps local and CI results identical. Images are scanned from `docker save` tarballs, so Trivy needs no socket.

**`FROM ${ARG}` without defaults.** Dockerfiles take image references only from build args that the Makefile fills from `deploy/versions.mk`, so there is one source of truth. Building a Dockerfile without the args fails on purpose.

**pnpm is installed with `npm install -g`.** Node 26 no longer ships corepack. This bootstrap step is outside pnpm's own `minimumReleaseAge` control; the pnpm version is pinned and Renovate-managed instead.

**Two exceptions to strict pinning.** The web runtime image runs `apk upgrade --no-cache` because the digest-pinned nginx base carried fixable HIGH CVEs (musl, zlib) that fail the scan gate; this makes that image depend on the build date until a fixed base is pinned, when the line should be removed. The API runtime image does the same since 2026-11-21 (slice 4b, owner decision D30): the pinned `eclipse-temurin` 25.0.5 JRE Alpine base carried a fixable HIGH CVE in `libfontenc` (CVE-2026-109908) and no fixed digest was published. In `apps/web`, `typescript-eslint` does not yet support TypeScript 7, so `typescript` is aliased to `@typescript/typescript6` for ESLint while `tsc` comes from the `typescript7` alias (TypeScript 7). `openapi-typescript` (slice 1) also uses the TypeScript JS API and resolves the TypeScript 6 alias; that is expected. Renovate PRs touching either alias need a manual check until the alias is dropped.

## Consequences

Everything is reproducible with `make`, at the cost of image pulls and a privileged API test step. Developers need Docker group access.
