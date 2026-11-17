# Slice 1 implementation brief: catalog browsing end to end

Branch: `slice-1-catalog`. Read first: `master-plan.md` (slice 1 line and the architecture table), `docs/adr/0001-containerised-tooling.md`, `docs/adr/0002-testing-strategy.md`, `tests/README.md`. All standing rules from slice 0 apply: every tool runs in a container through the Makefile, host UID, pinned versions in `deploy/versions.mk`, pnpm 7-day release-age gate, 100% coverage gates, no git index or history changes, no secrets, comments only for non-obvious workarounds, files under 500 lines, docker via `sg docker -c "..."` when the shell lacks the group.

## Goal

A visitor can browse, search, filter, sort and page through a seeded product catalog and open a product page, against a real database, in the built images, with an enforced Content Security Policy. Done means:

- `make dev`: `http://localhost:5173` shows the catalog; search, category filter, sort and pagination work and are reflected in the URL; a product page opens; `/status` shows the slice 0 API status.
- `make ci` passes: lint (including "generated API client is up to date"), tests at 100% coverage, build, scan, e2e (new catalog flow, CSP with zero violations, axe clean).

## Versions (read from registries 2026-11-17; npm versions are the newest at least 7 days old)

| Thing | Version |
|---|---|
| Spring Modulith BOM | 2.1.2 |
| springdoc-openapi (`springdoc-openapi-starter-webmvc-api`, no UI) | 3.2.0; confirm it supports Spring Boot 4.1 before relying on it, and report if not |
| `@tanstack/react-router` | 1.170.46 |
| `@tanstack/router-plugin` | 1.169.0 |
| `@tanstack/react-query` | 5.107.1 |
| `zod` | 4.6.7 |
| `tailwindcss`, `@tailwindcss/vite` | 4.3.5 |
| `shadcn` CLI | 4.23.0 |
| `openapi-typescript` / `openapi-fetch` | 7.13.0 / 0.17.0 |
| `lucide-react`, `tailwind-merge`, `class-variance-authority`, `clsx` | 1.70.0, 3.7.0, 0.7.1, 2.1.1 |

Spring Boot 4.1, Modulith 2, springdoc 3, TanStack Router, Tailwind 4, shadcn 4 and Zod 4 are newer than most training data: check official docs before trusting memory.

## API contract (fixed; the web and e2e agents build against exactly this)

All under `/api`. JSON. Errors are RFC 9457 Problem Details (`application/problem+json`) with `type`, `title`, `status`, `detail`; never a stack trace.

```
GET /api/categories
  200 -> Category[]                       ordered by name
GET /api/products?q=&category=&sort=&page=&size=
  q         optional, trimmed, max 100 chars; blank means no search
  category  optional category slug; unknown slug -> 200 with an empty page
  sort      relevance | name | price_asc | price_desc
            default: relevance when q is present, otherwise name
            relevance without q behaves as name
  page      0-based, default 0, min 0
  size      default 12, min 1, max 48
  200 -> ProductPage
  400 -> Problem Details for any invalid parameter
GET /api/products/{slug}
  200 -> ProductDetail
  404 -> Problem Details
GET /api/status                            unchanged from slice 0
```

Schemas (component names are part of the contract):

```
Money          { amountMinor: integer (int64), currency: string (ISO 4217, 3 letters) }
Category       { slug: string, name: string }
ProductSummary { slug: string, name: string, price: Money, category: Category }
ProductDetail  { slug: string, name: string, description: string, price: Money, category: Category }
ProductPage    { items: ProductSummary[], page: integer, size: integer, totalItems: integer (int64), totalPages: integer }
```

All listed properties are required and non-null in the OpenAPI document (so generated TypeScript types have no optional fields). Ordering is deterministic: every sort has `slug` as the final tiebreak.

## API design (`services/api`)

1. **Modules.** Spring Modulith with packages `dev.shop.api.catalog` (new) and `dev.shop.api.status` (existing). A `ModularityTest` runs `ApplicationModules.of(ApiApplication.class).verify()`. Only the controller-facing types are public where Modulith needs it; internals stay package-private or in an `internal` subpackage.
2. **Schema.** Flyway `V2__catalog.sql`: `category (id bigint identity, slug unique, name)`, `product (id bigint identity, slug unique, name, description, price_minor bigint check >= 0, currency char(3), category_id fk, active boolean default true, created_at, search_vector tsvector generated always as (setweight(to_tsvector('english', name), 'A') || setweight(to_tsvector('english', description), 'B')) stored)`, GIN index on `search_vector`, btree indexes that serve the sorts and the category filter. Inactive products never appear in any endpoint.
3. **Seed data.** Demo data is not part of the production schema. It lives in `src/main/resources/db/seed/R__seed_catalog.sql` (repeatable, idempotent: upsert by slug) and is applied only when profile `seed` is active (that profile adds `classpath:db/seed` to `spring.flyway.locations`). About 60 products in 6 categories, single currency EUR, varied prices and descriptions so search, sort and three pages of results are demonstrable. No product images in this slice (keeps the CSP strict and avoids hosting assets); the web shows a neutral placeholder.
4. **Data access.** `JdbcClient` with explicit SQL in one repository class. No JPA. Search uses `websearch_to_tsquery('english', :q)` with `ts_rank` for relevance. Pagination is offset-based with a separate count query. Parameters are always bound, never concatenated; the sort is chosen from an enum that maps to fixed SQL fragments.
5. **Validation and errors.** Request parameters validated (Bean Validation on a parameter record or explicit checks; implementer's choice) with a `@RestControllerAdvice` producing Problem Details for validation errors, type mismatches, not found, and unexpected errors (500 with a generic detail). `spring.mvc.problemdetails.enabled=true` or the Boot 4 equivalent.
6. **OpenAPI.** springdoc serves `/v3/api-docs` (no Swagger UI). The document is committed at `services/api/openapi/openapi.json`. An integration test `OpenApiContractIT` fetches `/v3/api-docs`, normalises it (stable key order, pretty printed, `servers` removed) and compares it with the committed file; when env `UPDATE_OPENAPI=true` it writes the file instead of comparing. The failure message tells the reader to run `make api-spec`.
7. **Logs and traces.** Structured JSON logs (Boot's built-in `logging.structured.format.console`, ECS format) by default; profile `dev` switches back to human-readable console logs. Micrometer Tracing with the OpenTelemetry bridge (`spring-boot-starter-opentelemetry` if that is the Boot 4.1 way; check): every request gets a trace, an incoming W3C `traceparent` is honoured, `traceId` and `spanId` appear in each log line. No exporter until slice 5: OTLP export is disabled explicitly so the app does not log connection errors. An integration test proves a request's log line carries the trace id from the `traceparent` it was sent.
8. **Response headers.** No Spring Security until slice 3. A small filter adds `X-Content-Type-Options: nosniff` to every response; catalog GET responses send `Cache-Control: no-cache` (revalidation, not `no-store`). Slice 3 replaces the filter with Spring Security's header writer; say so in the ADR.
9. **Config.** Profiles: none (production defaults: JSON logs, no seed), `dev` (readable logs), `seed` (demo data). Graceful shutdown enabled (`server.shutdown=graceful`). Probes unchanged.
10. **Tests.** Unit: controller slice tests with a mocked service (parameter defaults, bounds, error mapping), sort and paging logic, Problem Details shapes. Integration (shared Postgres context from slice 0): repository queries against the seed data (search hits and ranking, weight of name over description, category filter, each sort with tiebreak, page boundaries, inactive products hidden, unknown category), HTTP-level contract checks, `OpenApiContractIT`, trace-id log test, `ModularityTest`. The 100% line and branch gate now covers real branches; no exclusions.

## Web design (`apps/web`)

1. **Routing.** TanStack Router, file-based, with the Vite plugin. Routes: `/` (catalog), `/products/$slug` (product page), `/status` (the slice 0 status view, moved), a not-found route and a route error boundary. `src/routeTree.gen.ts` is generated and committed (needed for type checking), and excluded from ESLint and Prettier.
2. **Data.** TanStack Query. Route loaders call `queryClient.ensureQueryData` so navigation waits for data and links can preload. One `QueryClient` created in `main.tsx`, passed through router context.
3. **API client.** `openapi-typescript` generates `src/api/schema.d.ts` from `../../services/api/openapi/openapi.json`; `openapi-fetch` is the runtime client (`src/api/client.ts`, base URL same origin). Scripts: `generate:api` and `generate:api:check` (`openapi-typescript --check` or an equivalent that exits non-zero when the committed file is stale). `src/api/status.ts` moves onto the generated client if `/api/status` is in the spec; otherwise stays as is.
4. **URL state.** Catalog search params `q`, `category`, `sort`, `page` validated with Zod through the router's `validateSearch`; invalid values fall back to defaults instead of erroring. Changing search, category or sort resets `page`. The search box submits on Enter and on a 300 ms debounce; it never triggers a request per keystroke without debounce.
5. **UI.** Tailwind v4 (`@tailwindcss/vite`, CSS-first config) and shadcn/ui components copied into `src/components/ui/` by the pinned CLI (button, input, select, card, badge, skeleton, pagination or equivalents). System font stack, no web fonts, no external assets. Layout: header with shop name and nav, catalog grid (responsive 1 to 4 columns), product card (placeholder block, name, category badge, price), product page, footer. Prices formatted with `Intl.NumberFormat` from `amountMinor` and `currency`. States: loading skeletons, empty result ("No products match"), error with retry, product not found. Accessible: one `h1` per page, labelled controls, visible focus, pagination as a `nav` with `aria-current`, page title updated per route.
6. **CSP compatibility.** No inline scripts or `style` attributes in `index.html`, no `eval`, no data-URI scripts, no CSS-in-JS that injects `<style>` at runtime. If a shadcn/Radix component needs runtime-injected styles that the CSP below would block, pick a different primitive or a plain element instead of loosening the policy; report it.
7. **Coverage policy change.** Two categories are excluded from the 100% thresholds, and nothing else: machine-generated files (`src/routeTree.gen.ts`; `schema.d.ts` has no runtime code) and vendored shadcn primitives (`src/components/ui/**`), which are third-party source copied in unmodified. Everything written for this project stays at 100%. If a vendored primitive is edited, it moves out of `components/ui` and under the gate. ADR 0002 and `tests/README.md` are updated to say this. The owner approved exactly these two exclusions on 2026-11-17 and asked to be consulted before any other: no agent adds another exclusion, lowers a threshold or uses coverage-ignore comments; if something seems to need it, stop and report.
8. **Tests.** Vitest and Testing Library with `fetch` stubbed through one shared helper that answers by URL (no new mocking library). Cover: catalog rendering, search debounce and submit, filter, sort, pagination and URL sync, param validation fallbacks, loading, empty and error states with retry, product page, not found, price formatting, status route. Router tests use a memory history.

## Web image and headers (`apps/web/nginx.conf`, `apps/web/Dockerfile`)

- Enforced `Content-Security-Policy` on every response from the web image:
  `default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'`
- Also `X-Content-Type-Options: nosniff`, `Referrer-Policy: strict-origin-when-cross-origin`, `X-Frame-Options: DENY`, `Cross-Origin-Opener-Policy: same-origin`, `Permissions-Policy` denying camera, microphone, geolocation, payment.
- nginx `add_header` in a `location` discards inherited headers: put the header set in one included snippet and include it in every location that adds its own header. Headers are sent on error responses too (`always`).
- Caching: `index.html` and the SPA fallback `Cache-Control: no-cache`; hashed files under `/assets/` `public, max-age=31536000, immutable`.
- HSTS is not set here: there is no TLS locally, and it belongs on the Gateway that terminates TLS (slice 2 and the cloud guide). The dev server (Vite) does not enforce the CSP because HMR needs inline scripts; the policy is verified against the built image in e2e.

## End-to-end (`tests/e2e`)

- Stack: API runs with profile `seed`. Gateway unchanged.
- Specs: catalog lists seeded products with prices; search narrows results and updates the URL; category filter; sort by price; pagination forward and back; deep link with search params restores the view; product page from a card; unknown product shows not found; `/status` shows the API status; the simulated outage spec moves to `/status`.
- Security: a spec collects `securitypolicyviolation` events and console errors across the main flow and asserts none; a request-level spec asserts the header set on `/`, a deep link, and an `/assets/` file, and `nosniff` on `/api/products`.
- Accessibility: axe on the catalog (with results and empty), a product page, and not found.

## Platform (Makefile, compose, docs)

- `make api-spec`: runs `OpenApiContractIT` with `UPDATE_OPENAPI=true` to rewrite `services/api/openapi/openapi.json`. `make web-api-client`: runs `generate:api`. `make generate`: both, in order. `make lint` gains the client staleness check (`generate:api:check`); the spec staleness check is the integration test in `make test`.
- Dev compose: API gets `SPRING_PROFILES_ACTIVE=dev,seed`. E2E compose: `SPRING_PROFILES_ACTIVE=seed` (owned by the e2e agent).
- Docs: `docs/adr/0003-catalog-data-and-api.md` (JdbcClient over JPA, Postgres full-text search, offset pagination and its limits, money as minor units, seed data outside the production schema, committed OpenAPI document and generated client), `docs/adr/0004-web-security-headers.md` (the CSP and why each directive, where HSTS goes, dev server exception, header filter in the API until slice 3), ADR 0002 update for the coverage exclusions, root README (features, new targets, profiles), `tests/README.md` and `tests/manual/README.md` (manual: check the CSP in browser dev tools on the built image; try the catalog with keyboard only).

## Work split

Phase 1, in parallel (no overlapping files):
- **Agent API**: `services/api/`.
- **Agent WEB**: `apps/web/`. The real `services/api/openapi/openapi.json` does not exist until the API agent finishes, and the WEB agent must not write in `services/api`. So the WEB agent writes the contract above as a provisional OpenAPI file at `apps/web/openapi.provisional.json` and generates from that; the orchestrator switches the script to the real file at integration and deletes the provisional one.
- **Agent PLATFORM**: `Makefile`, `deploy/`, `.github/`, `renovate.json`, root `README.md`, `docs/adr/`, `tests/README.md`, `tests/manual/README.md`.

Phase 2, after phase 1 is integrated:
- **Agent E2E**: `tests/e2e/`.

Then the orchestrator runs `make ci` and `make dev`, and fresh Fable and Opus agents review the branch diff (`git diff main...` plus untracked files).

## Out of scope

Auth, cart, admin, images and uploads, product variants, faceted counts, typo-tolerant search, caching, Kubernetes, OTLP export.

---

# Amendments after the design challenge (these OVERRIDE anything above that conflicts)

## Contract and the provisional spec
- S1. There is no `apps/web/openapi.provisional.json`. The orchestrator has written the contract as a provisional document at `services/api/openapi/openapi.json` (info.version `provisional`). The WEB agent generates from it and never edits it. The API agent replaces it with springdoc's real output (`make api-spec` or the equivalent command) and its acceptance check is that the real document matches the provisional one structurally: same paths and methods, same query and path parameter names, the same enum values for `sort`, the same component schema names (`Money`, `Category`, `ProductSummary`, `ProductDetail`, `ProductPage`, `StatusResponse`) with the same property names and every property `required`. Differences in operationIds, descriptions, extra error responses (springdoc adds Problem Details responses from the advice; expected) and the exact `ProblemDetail` shape are fine. Report any structural difference instead of quietly changing the contract.
- S2. Required properties: springdoc does not mark record components required by default. Use one `OpenApiCustomizer` that marks every property of every component schema required unless annotated nullable, or `@Schema(requiredMode = REQUIRED)` on each component; `OpenApiContractIT` asserts `required` on `ProductPage`, `ProductSummary` and `Money`.
- S3. Query parameters appear in the spec as five flat parameters (`@ParameterObject` on a parameter record, or plain `@RequestParam`s). `sort` values on the wire are exactly `relevance`, `name`, `price_asc`, `price_desc` (a `String` validated against that set, or an enum with an explicit converter and matching schema values); the SQL fragment is a field of the enum, not a `switch`.
- S4. `page` max 10000 (400 above it); offset computed as `long`. A page past the end returns 200 with empty `items` and correct totals.
- S5. Add `spring-boot-starter-validation`. The advice extends `ResponseEntityExceptionHandler` so validation, binding, type-mismatch and no-resource errors become Problem Details without hand-written handlers; a catch-all handler returns a generic 500. `spring.mvc.problemdetails.enabled` is then unnecessary.

## API runtime
- S6. `/v3/api-docs` is disabled by default (`springdoc.api-docs.enabled: false`), enabled under profile `dev` and by property in `OpenApiContractIT`. Set `springdoc.writer-with-order-by-keys: true`. The test compares parsed JSON trees (key-order insensitive) and writes sorted, pretty-printed output with a trailing newline.
- S7. Observability config: `management.tracing.sampling.probability: 1.0`; `management.otlp.metrics.export.enabled: false`; `management.logging.export.otlp.enabled: false`; no tracing OTLP endpoint set. Verify the property names against the Boot 4.1 docs. An integration test asserts no OTLP metrics registry bean exists, so slice 5 turns export on deliberately. The app must start and idle without logging export errors (check the log).
- S8. Request logging: nothing logs one line per request by default. One filter logs method, path, status and duration at INFO and adds `X-Content-Type-Options: nosniff`. The trace test sends a `traceparent`, captures output, parses the JSON line and asserts its trace id field equals the sent trace id (confirm the field name the ECS formatter uses and state it in the report).
- S9. Structured logging is configured in a config document guarded by `spring.config.activate.on-profile: "!dev"`, not by unsetting a property in the dev profile.
- S10. Flyway: `spring.flyway.ignore-migration-patterns: "*:future,repeatable:missing"` so a database migrated with the `seed` profile still starts without it. One integration test covers migrate-with-seed then start-without-seed if it can be done without a third long-lived context; otherwise report and leave it to the manual checklist.
- S11. Tests do not depend on the seed content. Repository and HTTP integration tests use their own SQL fixtures (including an inactive product, equal names or prices for tiebreaks, and enough rows for page boundaries), cleaned per class. One smoke test under profile `seed` asserts 6 categories, at least 50 active products and at least 3 pages at the default size. Keep distinct Spring test context configurations to three at most (each starts a Postgres container). Expected orderings come from the database collation, not Java string comparison.
- S12. Schema details: `description text NOT NULL`; `currency text CHECK (currency ~ '^[A-Z]{3}$')`; indexes: GIN on `search_vector` and btree on `category_id` only (sort indexes wait for the slice 5 measurement). Modulith: only the test starter is needed; the shared advice and filter live in their own module package (for example `dev.shop.api.web`) so no cycle forms.

## Web
- S13. Scope cuts: search submits on Enter or a Search button only (no debounce). Category and sort are native `<select>` elements styled with Tailwind (Radix Select injects a `<style>` element at runtime, which the CSP blocks). Pagination and badge are plain hand-written elements. Loading states are text ("Loading products"), not skeletons. shadcn components used: button, input, card only. `src/lib/utils.ts` (the `cn` helper) is covered by tests, not excluded.
- S14. URL search params: `q`, `category`, `sort`, `page`. `page` in the URL is 1-based (the API is 0-based). Defaults are stripped from the URL (no `?page=1`, no `sort` unless the user chose one). The web sends `sort` to the API only when the user chose one.
- S15. Accessible names (the e2e agent relies on these): search input labelled "Search products" with a "Search" button; selects labelled "Category" (first option "All categories") and "Sort by" (options "Relevance", "Name", "Price: low to high", "Price: high to low"); pagination is a `nav` named "Pagination" with links "Previous" and "Next" and the current page marked `aria-current="page"`; each product card is an `article` containing a link whose name is the product name; `h1` is "Products" on the catalog, the product name on a product page, "Page not found" on unknown routes, "Product not found" for an unknown slug, "API status" on `/status`; empty result text "No products match"; a page past the end shows "No products on this page" with a link "Back to first page"; the error state has a "Try again" button. Document titles: "Products | Shop", "<name> | Shop", "API status | Shop".
- S16. API client: `createClient({ baseUrl: window.location.origin, fetch: (request) => globalThis.fetch(request) })` so tests can stub `fetch` after import and jsdom accepts the URL. The shared fetch stub handles a `Request` argument. Prove the abort-signal path works under jsdom in the first test written.
- S17. Spec location inside the tools container: the platform agent mounts `services/api/openapi` read-only at `/spec` in `WEB_RUN`; scripts read `${OPENAPI_SPEC:-../../services/api/openapi/openapi.json}`, and the Makefile sets `OPENAPI_SPEC=/spec/openapi.json`. The image build never regenerates the client; it uses the committed `src/api/schema.d.ts`. `schema.d.ts` and `routeTree.gen.ts` are in `.prettierignore` and the ESLint ignores (the staleness check compares raw generator output). `openapi-typescript` uses the TypeScript JS API and resolves the TypeScript 6 alias; that is expected.
- S18. Router: use mutually compatible releases of `@tanstack/react-router`, `@tanstack/router-plugin` and `@tanstack/router-cli` that pass the 7-day gate (the table versions are a ceiling; check peer dependencies). Plugin before `react()` in the Vite config, and disabled (or at least code splitting disabled) under Vitest so tests never rewrite `routeTree.gen.ts`. Scripts `generate:routes` (`tsr generate`) and `generate:routes:check` (fails when the committed tree is stale); `build` and `typecheck` must not depend on a freshly generated tree. shadcn's `@/*` alias uses `paths` without `baseUrl` plus a matching Vite alias. Any extra packages shadcn adds (`radix-ui`, `tw-animate-css`, others) must pass the release-age gate; list them in the report.
- S19. Loaders: `loaderDeps: ({ search }) => search`; `defaultPreloadStaleTime: 0`; a 404 from the product endpoint becomes `throw notFound()`; "Try again" calls `router.invalidate()`. Tests use a fresh `QueryClient` with `retry: false` and low `pendingMs` where a pending state is asserted. Use `fireEvent` (no `@testing-library/user-event`).
- S20. Add `public/favicon.svg` so the browser's favicon request does not fall back to `index.html`.
- S21. Known limit for ADR 0003 and the UI copy: full-text search matches whole words, not prefixes.

## nginx
- S22. Header snippet lives outside `conf.d` (for example `/etc/nginx/snippets/security-headers.conf`) and is included in the server block and in every location that adds a header. `location /assets/ { try_files $uri =404; }` so a missing hashed file is a 404, not a cached `index.html`. `server_tokens off`. `img-src 'self'` only (no `data:`) unless something needs it; report if so.

## Platform
- S23. `make api-spec` runs only `OpenApiContractIT` with `UPDATE_OPENAPI=true`, with the same socket, group and Testcontainers flags as `api-test`, and without the coverage gate (for example `-Dit.test=OpenApiContractIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true verify`; verify the exact flags work). Factor the shared docker flags into one variable. `make web-api-client` runs `generate:api`; `make generate` runs `api-spec`, `web-api-client` and `generate:routes` in order. `make lint` runs `generate:api:check` and `generate:routes:check`.
- S24. Renovate: group `springdoc` updates under their own name with a note in ADR 0003 that they can change the spec and need `make generate`; group the TanStack Router packages together.

## End-to-end
- S25. The CSP violation collector is registered with `page.addInitScript` before navigation and the flow covers catalog, product page, not found, status and one error state. E2E searches use whole words (S21). Selectors use the names in S15.
