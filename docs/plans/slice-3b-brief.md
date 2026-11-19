# Slice 3b implementation brief: admin product and stock writes, audit

Branch: `slice-3b-admin` (from `main` after PR #4 and #5 merged 3a). Read first: `CLAUDE.md`, `slice-3-brief.md` (C1, C18, C34, C36, C40 and D6 in its amendments), ADR 0003 and 0006, `docs/threat-model.md`, `tests/README.md`. All standing rules apply: every tool runs in a container through the Makefile, pins in `deploy/versions.mk`, the 7-day release-age gate, 100% coverage gates with no new exclusions, no git commands except read-only `git status` and `git diff`, no secrets in the repo or terminal, files under 500 lines, `sg docker -c "..."` when the shell lacks the docker group. High-risk slice: two challengers and two reviewers (general, security-only).

## Goal

Admins create, edit, deactivate and reactivate products, set stock, and read an audit log. Every change writes one audit row in the same transaction. Done means:

- `POST /api/admin/products`, `GET` and `PUT /api/admin/products/{id}`, `PUT /api/admin/products/{id}/stock` and `GET /api/admin/audit` work for admins, give 401 to anonymous and 403 to customers, and 403 without a CSRF token.
- Stale versions give 409, a duplicate slug gives 409, invalid input gives 400 with an `errors` extension; none of them writes an audit row.
- An inactive product is absent from the public list, search and product page; reactivating it brings it back.
- `make ci` passes (API and web at 100%); `make cluster-down cluster-up deploy cluster-e2e` passes, and a second run of `cluster-e2e` against the same persistent database passes too.
- ADR 0007 is written; the threat model, README, `tests/README.md` and the manual checklist cover the admin writes.

## What 3a actually shipped (and where it contradicts the slice 3 brief)

| Slice 3 brief said | Code on `main` | Decision for 3b |
|---|---|---|
| `AdminProduct` gains `version` and `stock`; lives with the admin API | `dev.shop.api.catalog.AdminProduct` and `AdminProductPage` are public records in `catalog`, returned by `ProductAdministration.list` and published as OpenAPI schemas `AdminProduct`, `AdminProductPage` | Adding `stock` there would make `catalog` depend on `inventory`. Rename the catalog records to `ManagedProduct` and `ManagedProductPage` (catalog's module API). The admin module defines new `AdminProduct` and `AdminProductPage` records that compose a `ManagedProduct` with a `StockLevel`. Schema names stay `AdminProduct` and `AdminProductPage`, so web code that imports them keeps compiling; the shapes gain `version` and `stock` |
| `customer` holds `CurrentUser` (C26) | No `CurrentUser`. `MeController` has a private `displayName(OidcUser)` | Add public `dev.shop.api.customer.CurrentUser` (below); `MeController` uses it |
| `admin` may depend on `catalog`, `inventory`, `customer` | `admin/package-info.java` allows `catalog` only | Allow `catalog`, `inventory`, `customer` |
| 400 validation with an `errors` extension | `ApiExceptionHandler` (`web`) extends `ResponseEntityExceptionHandler`, adds no `errors`. `AdminProductController` has constraint annotations on parameters, so Spring's built-in method validation applies and a bad `@Valid @RequestBody` arrives as `HandlerMethodValidationException`, not `MethodArgumentNotValidException` | Override both handlers (below) |
| Seed adds stock with `ON CONFLICT DO NOTHING` | `R__seed_catalog.sql` upserts products by slug and sets `active = true`, and does not touch any version | V3 backfills stock 0 before the repeatable seed runs, so `DO NOTHING` would leave every seed product at 0 on an existing database (kind, dev). Use the version-0 guard below. The product upsert must also bump `version` when it changes a row, or an open admin form would overwrite seed values without a 409 |
| `PUT` takes `ProductInput` including `slug` | Public URLs are `/products/{slug}` | D14: slug immutable after create |
| `Location` on 201 | C15: every `Location` is built from the path or `SHOP_PUBLIC_ORIGIN` | Relative `Location: /api/admin/products/{id}`; never `ServletUriComponentsBuilder` (it reads `Host`) |
| `admin.spec.ts` is repeatable because seed products are never edited | `pagination.spec.ts` asserts 60 active products (page 5 has 12, page 6 is empty); `catalog.spec.ts` asserts 10 kitchen products. Playwright runs spec files in parallel workers | Any active e2e product, even for a moment, breaks those specs. D16: project ordering and a cleanup setup |
| `AuthorizationMatrixIT` row for `POST /api/admin/products` | Expects 405 for an admin | Becomes 400 (empty body) for the admin; new rows for every new route |
| Count and page in one snapshot "when writes arrive" (ADR 0003) | Two statements without a shared snapshot in `ProductAdministration.list` and `CatalogService.search` | Both run in a read-only `REPEATABLE READ` transaction |

Everything else in 3a stays: the filter chain already sends every method on `/api/admin/**` through `hasRole("ADMIN")` and CSRF, so `auth` needs no change.

## Versions (read 2026-11-18 from registry.npmjs.org and tanstack.com)

| Thing | Pin | Notes |
|---|---|---|
| `@tanstack/react-form` | `1.33.5` (published 2026-08-11) | `latest` tag; peer `react ^17 \|\| ^18 \|\| ^19` (repo: React 19.3.1). `2.0.0-alpha.3` is alpha only. Pure React, no Vite plugin, so Vite 8 is not involved; types checked by `tsc` 7 in `make web-lint` |
| Transitive | `@tanstack/form-core` 1.33.5 (2026-08-11), `@tanstack/react-store` / `@tanstack/store` resolve to 0.11.2 (2026-09-29), `use-sync-external-store` ^1.6 | All far older than 7 days. Resolved by pnpm; no direct pins |
| Zod adapter | none | TanStack Form v1 accepts Standard Schema validators directly (tanstack.com Form validation guide, read 2026-11-18); Zod 4.6.7 (in the repo) implements Standard Schema. Do not add `@tanstack/zod-form-adapter` |
| API | none new | JDBC transactions, Bean Validation, Micrometer Tracing (`spring-boot-starter-opentelemetry`) and Jackson 3 are already on the classpath |

No image or tool changes, so `deploy/versions.mk` and the `Makefile` are untouched and there is no PLATFORM agent.

## Owner decisions needed

| # | Decision | Recommendation |
|---|---|---|
| D14 | Slug editable after create | **Immutable.** Public URLs and bookmarks stay valid, cache invalidation is by one key, and a freed slug cannot be reused to impersonate an old product. `PUT` takes no slug; a wrong slug means deactivate and create anew |
| D15 | How to deactivate and reactivate | **An `Active` checkbox in the product form**, sent in `PUT`; audit action `product.updated` with `active` in the changes. One endpoint, one audit shape. A separate endpoint adds code without new capability |
| D16 | Keeping e2e writes from breaking the count-asserting public specs on the persistent kind database | **Playwright projects:** `admin-cleanup` (setup) deactivates any active `e2e-` product, then `chromium` runs every other spec, then `admin-writes` runs the write spec. Cost: if any `chromium` spec fails, `admin-writes` is skipped (the run fails anyway). Alternative: rewrite the public specs to tolerate extra products, which weakens them |
| D17 | Protecting the audit table | **Insert-only in code plus a trigger that raises on `UPDATE` and `DELETE`.** The app's DB role owns the table, so `REVOKE` would not bind it; a separate role belongs in the cloud guide. The trigger stops accidental code paths and shows intent; the owner role can still drop it (residual risk) |
| D18 | Form controls | **Native `<input>`, `<textarea>`, `<select>`, checkbox, styled with Tailwind; reuse `components/ui/button` and `input`.** Adding shadcn primitives would grow the excluded `components/ui/**` code. Any new file there needs the owner's say-so |
| D19 | Seed stock on existing databases | **Seed sets `on_hand` only where `stock.version = 0`** (never edited by an admin). Fresh and existing databases both get seed stock; admin edits survive re-seeding |

The rest of this brief assumes the recommendations.

## API design (`services/api`)

**Modules** (Spring Modulith; `ModularityTest` must pass):

| Module | Adds | `allowedDependencies` |
|---|---|---|
| `catalog` | `ProductAdministration` (public): `list`, `get(id)`, `create(NewProduct)`, `update(id, expectedVersion, ProductChanges)`. Public records `ManagedProduct {id, slug, name, description, price: Money, category: Category, active, version}`, `ManagedProductPage`, `NewProduct`, `ProductChanges`, `ProductUpdate {before, after}`. Package-private exceptions | none (no `package-info` today; leave as is) |
| `inventory` (new) | `package-info.java` (`displayName = "inventory"`), `StockManagement` (public): `createFor(productId)`, `levelOf(productId)`, `levelsOf(Collection<Long>)`, `setOnHand(productId, expectedVersion, onHand)` returning `StockUpdate {before, after}`. Public record `StockLevel {onHand, reserved, version}`. Package-private `StockRepository`, exceptions | none |
| `customer` | Public record `CurrentUser(String subject, String displayName)` with `static CurrentUser of(Authentication)`: requires an `OidcUser` principal with a non-blank `sub` (else `AccessDeniedException`); display name is full name, else preferred username, else `sub`, cut to 200 characters | none |
| `admin` | `AdminProductController` (extended), `AdminAuditController`, `AdminProductService`, `AuditLog` (service), `AuditRepository`, DTOs `AdminProduct`, `AdminProductPage`, `ProductInput`, `ProductUpdateInput`, `StockInput`, `AuditEvent`, `AuditPage`, `FieldChange` | `catalog`, `inventory`, `customer` |
| `web` | `ApiExceptionHandler` validation overrides; OpenAPI `csrfHeader` scheme | unchanged |

**Endpoints** (all `Cache-Control: no-store`, `@SecurityRequirement(name = "sessionCookie")`; unsafe ones also `csrfHeader`):

| Method and path | Body | Success | Errors |
|---|---|---|---|
| `GET /api/admin/products?q&page&size` | | 200 `AdminProductPage` of `AdminProduct {id, slug, name, description, price, category, active, version, stock: {onHand, reserved, version}}` | 400 |
| `GET /api/admin/products/{id}` | | 200 `AdminProduct` | 400, 404 |
| `POST /api/admin/products` | `ProductInput {slug, name, description, priceMinor, categorySlug, active}` | 201 `AdminProduct`, `Location: /api/admin/products/{id}`; stock row with `onHand` 0 | 400, 409 duplicate slug |
| `PUT /api/admin/products/{id}` | `ProductUpdateInput {name, description, priceMinor, categorySlug, active, version}` | 200 `AdminProduct` | 400, 404, 409 stale version |
| `PUT /api/admin/products/{id}/stock` | `StockInput {onHand, version}` | 200 `AdminProduct` | 400 (incl. `onHand < reserved`), 404, 409 stale version |
| `GET /api/admin/audit?page&size` | | 200 `AuditPage` of `AuditEvent {id, occurredAt, actorSubject, actorName, action, entityType, entityId, traceId (nullable), changes: FieldChange[]}`, `FieldChange {field, from (nullable string), to (nullable string)}`; newest first | 400 |

**Validation** (Bean Validation; the web schemas mirror it exactly): `slug` `^[a-z0-9]+(-[a-z0-9]+)*$`, 1 to 100; `name` not blank, at most 120, `^[^\x00-\x1F\x7F]*$`; `description` not blank, at most 2000, `^[^\x00-\x09\x0B-\x1F\x7F]*$` (newline allowed); `priceMinor` 0 to 10 000 000; `onHand` 0 to 1 000 000; `version` 0 or more; `categorySlug` at most 100, must exist (400 on `categorySlug`). Numeric and boolean fields are boxed (`Long`, `Integer`, `Boolean`) with `@NotNull`, so a missing field is a 400 and not a silent 0 or false. `id` is a path `long` with `@Min(1)`. List bounds (C34): `size` 1 to 100 (default 20), `page` 0 to 10 000, `q` as in 3a. Currency is fixed `EUR`. Unknown JSON properties are ignored (records expose only the allowed fields; `id`, `version` on create, `reserved` and the like cannot be mass-assigned).

**Problem Details.** Every 400 and 409 from these endpoints is `application/problem+json` with `type` from a fixed set and an `errors` array of `{field, message}` (possibly empty):

| `type` | Status | Raised by |
|---|---|---|
| `tag:shop.dev,2026:validation` | 400 | `ApiExceptionHandler` overrides `handleMethodArgumentNotValid` and `handleHandlerMethodValidationException` (field = property path for bodies, parameter name for parameters); unknown category; `onHand < reserved` |
| `tag:shop.dev,2026:duplicate-slug` | 409 | `DuplicateKeyException` on the product insert (`errors: [{field: "slug"}]`) |
| `tag:shop.dev,2026:version-conflict` | 409 | stale product or stock `version` |

The 404 reuses `ProductNotFoundException` (add an id constructor). Domain exceptions extend `ErrorResponseException` as 3a's does; each module builds its own `ProblemDetail` (no shared exception class across modules). Messages never echo input values. Unreadable JSON stays Spring's 400. The OpenAPI document declares these responses with the existing `ProblemDetail` schema; `RequiredPropertiesCustomizer` would mark an `errors` property required on every problem, so the web parses `errors` itself with Zod instead of relying on generated types.

**Transactions and locking.** `AdminProductService` (`@PreAuthorize("hasRole('ADMIN')")` at class level) has one `@Transactional` method per change: create, update, set stock. Each calls the catalog or inventory API (which joins the transaction), then `AuditLog.record(...)`. If the audit insert fails, the change rolls back. Updates are check-then-write under a row lock: `SELECT ... FOR UPDATE` by id (404 if absent), compare `version` (409), validate (400), `UPDATE ... SET ..., version = version + 1, updated_at = now()`. Each change locks one row, so there is no lock-ordering deadlock. A `PUT` that changes nothing returns 200 with the current state, no version bump and no audit row (the version is still checked first). Reads (`list`, `get`, audit page) are `@Transactional(readOnly = true, isolation = REPEATABLE_READ)` so count, page and stock levels come from one snapshot; `CatalogService.search` gets the same. `ProductAdministration`'s write methods and `StockManagement.setOnHand` and `createFor` carry method-level `@PreAuthorize("hasRole('ADMIN')")` as defence in depth (method-level, because slice 4 adds customer-facing stock methods).

**Identity (C18).** The controller passes `CurrentUser.of(authentication)` to the service. The audit row stores `sub` as the actor; `actor_name` is display data at the time of the change, shown but never used for decisions.

**Audit.** `AuditLog.record(actor, action, entityType, entityId, changes)` inserts only; the repository has `insert` and `page`, nothing else. Actions: `product.created`, `product.updated`, `stock.set`; entity types `product`, `stock` (entity id = product id). `changes` is a jsonb object of changed fields only, `{"field": {"from": x, "to": y}}`, with JSON-typed values (`from: null` for every field on create). Fields: `slug`, `name`, `description`, `priceMinor`, `categorySlug`, `active`, `onHand`. The API returns them as `FieldChange[]` sorted by field, values rendered as strings. `trace_id` comes from Micrometer's `Tracer` (`currentSpan()`; null when none). jsonb is written with `CAST(:changes AS jsonb)` from Jackson 3 (`tools.jackson.databind.json.JsonMapper`) and read as `changes::text`.

**Migrations** (`src/main/resources/db/migration`; V1 and V2 exist):

- `V3__stock.sql`: `stock(product_id bigint PRIMARY KEY REFERENCES product(id), on_hand integer NOT NULL CHECK (on_hand >= 0), reserved integer NOT NULL DEFAULT 0 CHECK (reserved >= 0), version bigint NOT NULL DEFAULT 0, CHECK (reserved <= on_hand))`; `INSERT INTO stock (product_id, on_hand) SELECT id, 0 FROM product`.
- `V4__product_admin.sql`: `product.version bigint NOT NULL DEFAULT 0`, `product.updated_at timestamptz`.
- `V5__admin_audit.sql`: `admin_audit_event(id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, occurred_at timestamptz NOT NULL DEFAULT now(), actor_subject text NOT NULL, actor_name text NOT NULL, action text NOT NULL, entity_type text NOT NULL, entity_id bigint NOT NULL, changes jsonb NOT NULL, trace_id text)`, index `(occurred_at DESC, id DESC)`, no foreign key to `product` (the log outlives rows and survives fixture truncation), and a `BEFORE UPDATE OR DELETE` row trigger that raises (D17). `TRUNCATE` stays possible for tests.
- `db/seed/R__seed_catalog.sql`: the product upsert updates only rows that differ (`WHERE (product.name, ...) IS DISTINCT FROM (EXCLUDED.name, ...)`) and bumps `version`. Then `INSERT INTO stock ... SELECT p.id, <seed quantity> FROM product p WHERE p.slug IN (seed slugs) ON CONFLICT (product_id) DO UPDATE SET on_hand = EXCLUDED.on_hand WHERE stock.version = 0` (D19). Seed quantities are any deterministic non-zero values. The seed still re-activates a seed product an admin deactivated, when the seed file changes (documented in ADR 0007).

**Public catalog.** Already filters `p.active` in list, count and `findBySlug` (3a). 3b proves it end to end through the admin API (`AdminProductIT`): deactivate, then the product is gone from `/api/products`, search, its category filter and `/api/products/{slug}` (404); reactivate brings it back. Public responses keep `Cache-Control: no-cache`.

**Tests** (100% line and branch, no exclusions):

- `AdminProductIT`: create (201, relative `Location`, stock 0, audit row), get, update, 400 for every rule with the right `errors[].field` (including missing boxed fields, control characters, an unknown category, an over-long slug), duplicate slug 409 with `errors[0].field == "slug"`, stale version 409, unknown id 404, no-op `PUT`, the public visibility round trip, no audit row for any failed call.
- `StockIT`: set stock, stale version 409, bounds, `onHand < reserved` 400 (set `reserved` through SQL), two concurrent `PUT`s with the same version give one 200 and one 409 and one audit row.
- `AuditIT`: rows per action with `sub`, name, changed fields only and a trace id when a `traceparent` is sent; paging and bounds; newest first; an audit insert forced to fail (`@MockitoSpyBean` on the repository) rolls back the product change; SQL `UPDATE` and `DELETE` on the table fail (trigger).
- `AdminEndpointsAreProtectedIT` also asserts that every public method of `AdminProductService` and `AuditLog`, and the write methods of `ProductAdministration` and `StockManagement`, carry `hasRole('ADMIN')`. `MethodSecurityDenialTest` covers the new controllers. `AuthorizationMatrixIT` gets rows for every new route and method (anonymous 401, customer 403, admin 200/201/400; no CSRF 403).
- Unit tests for `CurrentUser`, the diff builder and the exception handler overrides. `SeedProfileIT` checks seed stock and that a re-run keeps an edited stock row. Existing ITs keep passing; `CatalogFixtures` gains stock and audit helpers and its `clear` truncates `stock` and `admin_audit_event`.

## Web (`apps/web`)

- **Dependency:** `@tanstack/react-form` 1.33.5 (exact pin, as the rest of `package.json`).
- **Zod and the CSP:** every schema imports `z` from `@/lib/zod` (jitless). Add an ESLint `no-restricted-imports` rule forbidding `'zod'` outside `src/lib/zod.ts`.
- **`src/lib/adminSchemas.ts`:** `productFormSchema`, `productUpdateSchema`, `stockFormSchema` mirroring every server rule above (same regexes, lengths, bounds). Price is typed in euros as a string (`^\d{1,6}(\.\d{1,2})?$`) and converted to minor units by string arithmetic, never through floats; at most 100 000.00. `problemSchema` parses error bodies (`type`, `detail`, `errors` defaulting to `[]`); anything unparseable is a generic failure.
- **`src/api/admin.ts`:** `adminProductQuery(id)`, `auditQuery(search)`, and mutation functions `createProduct`, `updateProduct`, `setStock` through `apiClient` (the existing middleware adds `X-XSRF-TOKEN`, reading the cookie fresh). On 400 or 409 they throw an `AdminProblemError` carrying the parsed problem. After success: `setQueryData` for the product, then invalidate `['admin']`, `['products']` and `['product', slug]`.
- **Routes:** `admin.products.new.tsx` (`/admin/products/new`), `admin.products.$id.tsx` (`/admin/products/$id`; `params` parsed as a positive integer, otherwise the not-found panel), `admin.audit.tsx` (`/admin/audit`, `page` search param like `adminSearch`). Data loads through `useQuery` in components, as `admin.index.tsx` does, so the `admin.tsx` layout decides anonymous and customer screens before any admin call. Each view also handles 401 and 403 like `AdminProductsView`, and 404 with the not-found panel.
- **`admin.tsx` layout** gains a nav (`Products`, `Audit log`). **`AdminProductTable`** gains a Stock column, the name links to the edit page, and the page gets a `New product` link.
- **Forms (TanStack Form):** `ProductForm` (create and edit), `StockForm`. Validators: the Zod schemas on `onChange` and `onSubmit` (Standard Schema, no adapter). Submit runs the mutation in `onSubmitAsync`; on a validation or duplicate-slug problem it returns `{ form, fields }` so server messages appear under fields; field names the form does not know go to the form-level alert. On version conflict: an alert "This product was changed by someone else." with a `Reload` button that refetches and resets the form to the fresh values. Accessibility: every control labelled, `aria-invalid` and `aria-describedby` on errors, focus moves to the first invalid field or the alert, success shown in a `role="status"` region, submit disabled while pending.
- **Audit view:** table of time (`Intl.DateTimeFormat`), actor name, action, product link, and the changes as a list `field: from → to`. All values rendered as text (React escapes them; no `dangerouslySetInnerHTML`).
- **Tests:** 100% of the new code: schemas (each rule, price conversion edge cases), the problem parser, every view state (loading, 401, 403, 404, error, success, 400 field mapping, unknown-field fallback, 409 and reload), query invalidation, the CSRF header on `POST` and `PUT`. Fixtures in `src/test/fixtures.ts` gain `version` and `stock`.

**Fixed UI text** (E2E depends on it): headings `New product`, `Edit product`, `Audit log`; links `New product`, `Products`, `Audit log`; labels `Slug`, `Name`, `Description`, `Price (EUR)`, `Category`, `Active`, `On hand`; buttons `Create product`, `Save changes`, `Save stock`, `Reload`; status texts `Product saved`, `Stock saved`; conflict alert as above.

## E2E (`tests/e2e`)

- **Projects (D16)** in `playwright.config.ts`: `admin-cleanup` (`testMatch: /admin-cleanup\.setup\.ts/`), `chromium` (depends on `admin-cleanup`, ignores the setup and `admin-writes.spec.ts`), `admin-writes` (depends on `chromium`). The cleanup signs in as admin, lists `q=e2e-` with `size=100` and `PUT`s `active: false` on any active match. `tests/README.md` notes `--no-deps` for running one file without its dependencies.
- **`support/auth.ts`:** `apiFetch` accepts a JSON body (and sets `Content-Type`). **`support/admin.ts`:** a unique slug `e2e-<Date.now()>-<4 random hex>` and a matching name `E2E Quokka <same token>`; names avoid every word the public specs search for.
- **`specs/admin-writes.spec.ts`** (serial, one admin context; a `finally` deactivates the product): create inactive (Garden, 12.34); client-side error on slug `Bad Slug` with no request sent; server 409 on slug `oak-desk` shown under Slug; activate and change price, then the public product page and search show it; set stock 7 and see it in the table; conflict with two pages on the same product (second save shows the alert, `Reload` recovers); deactivate, then the public page shows "Product not found"; the audit log shows `product.created`, `product.updated` and `stock.set` rows for the product by `Test Admin` (no exact counts); a customer's `PUT` gets 403 and a `PUT` without the CSRF header gets 403 problem+json.
- **`admin.spec.ts`** keeps the read-only checks; `csrf.spec.ts` adds a `PUT` without token. **`signed-in.spec.ts`** visits `/admin/products/new`, `/admin/audit` and the edit page of `oak-desk` (id from the admin list API) for CSP, and runs axe on each, plus the new-product form with a validation error showing. No seed product is ever written.
- Both stacks run the same specs. Never assert exact audit or product counts in admin views; the kind database persists between runs.

## Docs (DOCS)

- `docs/adr/0007-admin-product-and-stock.md`: admin API and module split (`ManagedProduct` versus `AdminProduct`), optimistic locking by version in the body with row locks, 409 and 400 shapes and `type` values, immutable slug, the stock model now (`on_hand`, `reserved`, version) and in slice 4, the audit log (same transaction, `sub`, changed fields only, trace id, trigger, no FK), seed interactions (D19, reactivation), deny-by-default unchanged, e2e isolation (D16).
- `docs/threat-model.md`: admin section: assets (catalog integrity, audit log), threats with mitigations (CSRF on writes, method security plus URL rule, mass assignment, input abuse and control characters, stored XSS through names and descriptions, lost updates, audit tampering and repudiation, enumeration by id is admin-only), residual risks: owner DB role can bypass the trigger; display name in audit is user-editable; request body size is not capped until slice 5's gateway limits; a demoted admin keeps write access for up to the revalidation interval (R14).
- `README.md` (admin features, known limits), `tests/README.md` (new ITs, counts, projects and `--no-deps`), `tests/manual/README.md` (an admin edits in two tabs; keyboard-only form use and a screen-reader pass of the form errors; reading the audit log after a demotion).

## Work split (Sonnet implementers; no shared files)

| Phase | Agent | Owns |
|---|---|---|
| 1 | API-ADMIN (C36) | `services/api/**`. First deliverable: the contract (records, controllers with signatures and annotations, stub services), then `make api-spec` so `openapi.json` is final before phase 2 starts |
| 1 | DOCS | `docs/adr/0007*`, `docs/threat-model.md` drafts |
| 2 (after the contract) | WEB | `apps/web/**` except generated files; runs `make web-api-client` and `generate:routes` |
| 2 (after the contract) | E2E | `tests/e2e/**` except `cluster/`; codes against the fixed UI text, run once WEB is done |
| 3 | DOCS | `README.md`, `tests/README.md`, `tests/manual/README.md`, ADR 0007 finalised with measured numbers |

**Fixed interfaces:** the endpoint table and JSON field names; the three problem `type` values and `errors: [{field, message}]`; action names `product.created`, `product.updated`, `stock.set`; field names in `changes`; the fixed UI text; the slug prefix `e2e-`; the Playwright project names. A change to any of these goes through the orchestrator and the brief's amendments.

**Verification per agent:** API-ADMIN `make api-lint api-test api-spec` (the second `api-spec` gives no diff), then `make api-build`. WEB `make web-lint web-test web-build`. E2E `make e2e-lint`, then `make e2e` once WEB and API are in. DOCS: links resolve, files under 500 lines.

**Orchestrator integration:** `make generate` (no diff in `git status`), `make lint`, `make ci`, `make cluster-down cluster-up deploy cluster-e2e`, then `make cluster-e2e` again (repeatability on the persistent database), then a second `make deploy` (no pod restarts) and `make dev` with a manual create, edit and stock change at http://localhost:5173. Record the new migrations applying to the existing kind database in STATUS.

## Out of scope

Category management, images, hard delete, bulk edits, CSV import, an audit filter or export, rate limits and request-size limits (slice 5), stock reservations (slice 4), a separate audit DB role (cloud guide), ETag or `If-Match` concurrency.

## Known traps

- CLAUDE.md traps apply: Jackson 3 is `tools.jackson.*` (jsonb serialisation too); Testcontainers 2 names; the CSP shows up only in `make e2e`.
- Method validation: with constraint annotations on any parameter, a bad `@Valid @RequestBody` raises `HandlerMethodValidationException`. Handle both exception types and test both.
- Boxed types with `@NotNull`, or a missing `version` becomes 0 and a missing `active` becomes false.
- `DuplicateKeyException` must be caught for the slug constraint only; any other integrity error stays a 500 and is a bug.
- `@Transactional` and `@PreAuthorize` work through proxies: no self-invocation inside a service. Spring's `isolation` is ignored when a method joins an outer transaction, so set it on the outermost read method.
- Flyway: V3 runs before `R__seed_catalog.sql`; the repeatable seed re-runs only when its checksum changes. Never edit V1 or V2.
- Springdoc names schemas by simple class name: `catalog.ManagedProduct` and `admin.AdminProduct` must not share a name. Run `make generate` after any DTO change; `make lint` and `make test` catch staleness.
- TanStack Form: a field-level validator can overwrite a form-level server error for that field; keep server errors in the form-level `onSubmitAsync` result and let the next change clear them. Import Zod only from `@/lib/zod`.
- Price in euros: never `parseFloat` and multiply; `0.1 + 0.2` problems surface as off-by-one cents.
- Playwright project dependencies run in full even with a file filter; use `--no-deps` for a single file.
- Kind: the database persists; slugs are unique per run and every created product ends inactive.

## Top risks

1. A momentarily active e2e product breaking public count specs in parallel workers: D16 ordering, cleanup setup, `finally` deactivation.
2. Seed and admin edits fighting on dev and kind databases: version bump in the seed upsert and the stock version-0 guard (D19), covered by `SeedProfileIT`.
3. Server and web validation drifting apart: one table of rules in this brief; tests on both sides use the same boundary values (100, 101, 120, 121, 2000, 2001, 10 000 000, 10 000 001).
4. Audit and change not atomic: one transaction per change, proven by the forced-failure IT.
5. CSP regressions from the form library or Zod: `signed-in.spec.ts` covers the new pages in both stacks.

## Amendments (2026-11-18; override the body above)

Sources: general challenger (G, `build/reports/slice-3b-challenge-general.md`), security and failure-mode challenger (S, `build/reports/slice-3b-challenge-security.md`), owner decisions (O). Rationale for the owner decisions: https://claude.ai/artifact/99ajco2b4YKXDQwW4rvbC2. The agent that owns each change is in brackets.

### Owner decisions (final)

- **E1 (O): D14, D15 and D18 as recommended.** D16, D17 and D19 as recommended with the conditions in E2 to E4.
- **E2 (O, D16; G2, S15, G20) [E2E].** The setup file is `tests/e2e/specs/admin-cleanup.setup.ts`; the default `testMatch` does not pick it up, so `chromium` only ignores `admin-writes.spec.ts`. The cleanup pages `GET /api/admin/products?q=e2e-&size=100` from page 0 until `page + 1 >= totalPages`, deactivates every active item whose `slug` starts with `e2e-` (filtered client-side; a name containing `e2e-` is never touched), then lists again and asserts none is left active. Helpers live in `tests/e2e/specs/support/` (`admin.ts` there, not `tests/e2e/support/`). Every e2e product gets the fixed description `Temporary product made by an end-to-end test.`, which contains none of the words the public specs search for (`oak`, `kettle`, `kett`, `lantern`, `zzzzqx`). The public search assertion searches for `Quokka`, not the slug token. [DOCS] ADR 0007 records that inactive e2e rows accumulate on kind by design.
- **E3 (O, D17; S10) [API-ADMIN].** V5 adds a statement-level `BEFORE TRUNCATE` trigger on `admin_audit_event` that raises, next to the row trigger. Tests no longer truncate the audit table: `CatalogFixtures` gains `auditHighWaterMark()` (current `max(id)`, 0 when empty) and every audit assertion reads only rows above the mark taken at the start of the test. `AuditIT` also asserts that `TRUNCATE admin_audit_event` fails. [DOCS] Residual risk: the owner DB role can drop or disable the triggers and then update, delete or truncate; a separate audit role is in the cloud guide. This replaces "`TRUNCATE` stays possible for tests" in the body.
- **E4 (O, D19; G3, S5, S4, G17) [API-ADMIN].** The seed stock guard is `ON CONFLICT (product_id) DO UPDATE SET on_hand = EXCLUDED.on_hand WHERE stock.version = 0 AND stock.reserved <= EXCLUDED.on_hand` (G3 and S5 are the same fix). The seed stock upsert never touches `stock.version`, or the next seed change would stop updating the row. The seed quantity goes into the existing `VALUES` list of the product insert, and stock is written by a data-modifying CTE or a second statement joined on slug, not a 60-slug `IN` list. [DOCS] ADR 0007 states the stock version rule as fixed for slice 4: every write that changes `on_hand` bumps `stock.version`; writes that change only `reserved` do not (so checkouts do not make every open admin form conflict), and `setOnHand` checks `onHand >= reserved` against the row it locked.
- **E5 (O; S6) [API-ADMIN]. Admin writes fail closed when revalidation is overdue.** In `auth/RevalidationFilter.doFilterInternal`, after the existing revalidation step and before `chain.doFilter`: if the authentication is an `OAuth2AuthenticationToken`, the method is not `GET`, `HEAD`, `OPTIONS` or `TRACE`, the path starts with `/api/admin/`, and `LAST_REVALIDATION_SUCCESS` (set at login by `LoginSuccessHandler` and on every successful refresh) is absent or not after `now - revalidateAfter`, write a 503 problem+json through `ProblemResponses` (inject it; detail "Your access cannot be confirmed right now") and stop the chain. A successful refresh in the same request sets the success time to now, so it passes; a rejected refresh has already ended the session (401). This covers Keycloak being unreachable within the grace, and also a request that loses the single-flight lock while the success time is overdue (it gets 503 and may retry). Reads keep the 15-minute grace. The filter runs before `CsrfFilter`, so such a request gets 503 even without a CSRF token; that is accepted. Tests: `RevalidationIT` adds "a demoted admin with the IdP down gets 503 problem+json on `PUT /api/admin/products/{id}` and 200 on `GET /api/admin/products` within the grace" and "after the IdP recovers the same `PUT` succeeds"; `RevalidationFilterTest` covers each branch (safe method, non-admin path, fresh success, missing success time). No audit row is written for a 503. [DOCS] R14 in the threat model is reworded: a demoted admin can write for up to `revalidateAfter` (5 min) while Keycloak is up; with Keycloak down, writes stop 5 minutes after the last success and reads continue up to 15.
- **E6 (O; S7) [API-ADMIN, DOCS, WEB].** `traceparent` is still accepted. `AuditIT` asserts the stored trace id is 32 lowercase hex characters, and that a sent `traceparent` is the one stored. ADR 0007 and the threat model state that the audit trace id is client-controlled, an untrusted correlation hint and never evidence. The audit view's column header reads `Trace (unverified)` and the field's OpenAPI description says the same. No server request id is added (not part of the owner decision).

### API (`services/api`)

- **E7 (G6, S8; merged) [API-ADMIN].** The product insert is `INSERT ... ON CONFLICT (slug) DO NOTHING RETURNING id`; an empty result raises the `duplicate-slug` problem. No `DuplicateKeyException` is caught anywhere, so a duplicate `stock` key or any other integrity error stays a 500, and the transaction is never left aborted. The "caught for the slug constraint only" trap is void.
- **E8 (S1) [API-ADMIN].** A Boot 4 `JsonMapperBuilderCustomizer` in `web` disables `DeserializationFeature.ACCEPT_FLOAT_AS_INT` and `MapperFeature.ALLOW_COERCION_OF_SCALARS` (check the names against the Jackson 3 sources; if scalar coercion moved to `coercionConfigDefaults`, use that). `OidcLoginIT`, `BackChannelLogoutIT` and `RevalidationIT` must still pass. `AdminProductIT` asserts 400 with the right `errors[].field` for `priceMinor: 12.5`, `onHand: 1.0e0`, `version: 1.5`, `active: "true"` and `priceMinor: "1299"`.
- **E9 (S2, G15, G19) [API-ADMIN, WEB].** `name`, `description` and `CurrentUser.displayName` use one shared constraint `@PlainText(allowNewline = false|true)` in `web` (a constraint annotation plus validator), checked on code points: reject `Character.getType` in CONTROL, FORMAT, SURROGATE, PRIVATE_USE or UNASSIGNED, except `\n` where newlines are allowed. This replaces the two `\x` patterns for `name` and `description` in the body (the 3a `q` pattern stays). The OpenAPI schema carries the ECMA pattern `^[^\p{Cc}\p{Cf}\p{Cs}\p{Co}\p{Cn}]*$` (description: `^(?:\n|[^\p{Cc}\p{Cf}\p{Cs}\p{Co}\p{Cn}])*$`) through `@Schema(pattern = ...)`; the web schemas use the same patterns with the `u` flag. Tabs and CR stay rejected in descriptions; ADR 0007 and the `description` OpenAPI text say so (G19). Not blank means at least one character above U+0020 on both sides (Hibernate's `@NotBlank` uses `String.trim`, JS `trim()` also strips NBSP), so a name of only U+00A0 passes both. Boundary tests on both sides: U+0085, U+200B, U+202E, a lone `\ud800`, U+E000 (400 with the field set) and U+00A0 alone (accepted). `CurrentUser.displayName` removes disallowed code points instead of rejecting (it comes from Keycloak, not a form), then cuts at 200 code points so a surrogate pair is never split.
- **E10 (S3) [API-ADMIN].** The app's connections get `lock_timeout = 3s` through `spring.datasource.hikari.data-source-properties.options: "-c lock_timeout=3s"` in `application.yaml` (one place, covers every `FOR UPDATE`). `ApiExceptionHandler` maps `PessimisticLockingFailureException` (which includes `CannotAcquireLockException`) to 503 problem+json with `Retry-After: 3` and no audit row; not a 409, because the version may be current. `StockIT` holds the row lock from a second connection and asserts the 503. `ValkeyFailureFilter.isValkeyFailure` matches `RedisConnectionFailureException`, or a `QueryTimeoutException` only when its cause chain holds an `io.lettuce.core.RedisCommandTimeoutException`; `ValkeyFailureFilterTest` adds a JDBC `QueryTimeoutException` that is rethrown.
- **E11 (G4) [API-ADMIN].** Every product has a stock row. `CatalogFixtures.addProduct` also inserts `stock(product_id, on_hand)` (default 0). `StockManagement.levelsOf` returns a level for every requested id and throws `IllegalStateException` for a missing row (a bug, so 500; unit-tested). `CatalogFixtures.clear` runs `TRUNCATE stock, product, category RESTART IDENTITY CASCADE` and leaves the audit table alone (E3).
- **E12 (G5, S5) [API-ADMIN].** `SeedProfileIT` runs the seed script directly with `ResourceDatabasePopulator` (`classpath:db/seed/R__seed_catalog.sql`) instead of restarting the app, after setting one seed stock row to `version = 1` and another to `reserved` above its seed quantity. It asserts: both rows unchanged, version-0 rows hold the seed quantities, no product `version` changed (proving `IS DISTINCT FROM`), no `stock.version` changed, and a second run is idempotent.
- **E13 (G9) [API-ADMIN].** Write methods run at the default `READ COMMITTED`, stated explicitly on the `@Transactional` annotations; `REPEATABLE_READ` would turn the waiting writer into a serialization failure (500) instead of a 409. `StockIT` adds a deterministic lock test: a test transaction locks the stock row with `SELECT ... FOR UPDATE`, starts the `PUT` asynchronously, asserts it has not completed after about 300 ms, bumps the version and commits, then asserts the `PUT` returns 409 and no audit row was written for it. The two-request race stays as a smoke test. The same lock test for products goes in `AdminProductIT`.
- **E14 (G10) [API-ADMIN].** Every `@MockitoSpyBean` lives in one class, `AuditRollbackIT`, so only one extra application context (with its own containers) starts.
- **E15 (G11) [API-ADMIN].** Method-security tests for write endpoints use `SecurityMockMvcRequestPostProcessors.oidcLogin()` with `ROLE_ADMIN` and a `sub`. A test asserts that a non-OIDC admin (`AuthIT.adminPrincipal()`) gets 403 on every write and writes no audit row.
- **E16 (G12) [API-ADMIN].** `/api/me` stays as it is: `MeController` keeps its own `displayName` (nullable `name`). `CurrentUser` is used only by the admin writes. This replaces "`MeController` uses it" in the body; `Me` tests and the web `UserMenu` do not change.
- **E17 (G13) [API-ADMIN].** `AuditEvent.traceId`, `FieldChange.from` and `FieldChange.to` carry `@Schema(nullable = true)` (as `Me.java`). `admin.AdminProduct` is a flat record built by `static AdminProduct of(ManagedProduct, StockLevel)`; no nested `ManagedProduct` and no `@JsonUnwrapped`. Its `stock` field is a new admin record `AdminStock {onHand, reserved, version}`, so `inventory.StockLevel` is not in the published contract. Jackson annotations stay `com.fasterxml.jackson.annotation`.
- **E18 (G14) [API-ADMIN].** `AdminProductIT` posts a create body with extra `"id": 999, "version": 7, "reserved": 5, "stock": {...}` and asserts the id is generated, `version` is 0 and stock is 0/0; the same for `PUT` with `slug` and `id`.
- **E19 (G16, S14) [API-ADMIN].** The new validation handler applies to public endpoints too; accept it, add one `CatalogHttpIT` assertion (`/api/products?size=49` gives `type: tag:shop.dev,2026:validation` with `errors`) and one line in ADR 0007. `ProductNotFoundException` uses the fixed detail `Product not found` for both the slug and the new id constructor (update `CatalogServiceTest` and `CatalogControllerTest`). The catch-all in `ApiExceptionHandler` logs a `DataIntegrityViolationException` (and any `DataAccessException` with an `SQLException` cause) as its class, SQLState and constraint name only, never the message, since PostgreSQL's "Failing row contains" detail holds product text.
- **E20 (S9) [API-ADMIN].** `ProductAdministration` gets a class-level `@PreAuthorize("hasRole('ADMIN')")` (reads included), and `AdminEndpointsAreProtectedIT` asserts every public method of it. `StockManagement` keeps method-level guards on `setOnHand` and `createFor` only.
- **E21 (S11) [API-ADMIN].** `occurred_at DEFAULT clock_timestamp()`, and the audit page is ordered by `id DESC` alone (the primary key index serves it; drop the `(occurred_at DESC, id DESC)` index). `occurred_at` is displayed only.
- **E22 (S4) [API-ADMIN].** The `stock.set` audit row also records `reserved` at the time of the change as `{"reserved": {"from": r, "to": r}}`, the one unchanged field recorded (context for the `onHand` change). `reserved` joins the fixed field names in `changes`.
- **E23 (S16) [API-ADMIN].** V3, V4 and V5 each begin with `SET LOCAL lock_timeout = '5s'`, so a migration blocked by long reads fails and the pod restarts instead of stalling the catalog. ADR 0007 notes it.
- **E24 (G18) [API-ADMIN].** Drop `product.updated_at` from V4 and the `UPDATE`; the audit log records when.
- **E25 (S13) [API-ADMIN].** No body-size filter in 3b. `AuthorizationMatrixIT` adds anonymous and customer `POST /api/admin/products` with a malformed 1 MiB body: 401 and 403 problem+json, not 400, which shows the body is refused before it is parsed. The admin-side gap stays a residual risk until slice 5.

### Web (`apps/web`)

- **E26 (G1) [WEB].** After a 200, `ProductForm` and `StockForm` call `form.reset(toFormValues(response))`, so `version` (form state, not a visible field) comes from the response. Tests: two saves in a row on one mounted form send versions n and n+1; saving stock then product (and the reverse) on the edit page sends the right version for each.
- **E27 (G7) [WEB].** (a) A test pins whether a server field error clears when the field changes; if TanStack Form keeps it, clear it in that field's `onChange` listener with `form.setFieldMeta`. (b) The Zod schemas validate the price string only; euros to minor units is a plain function called in the submit path and tested on its own (no `.transform`). (c) The mutation runs in `onSubmitAsync` only after the sync `onSubmit` schema passes; a test asserts an invalid form sends no request.
- **E28 (G8) [WEB, E2E].** On 201 the create form navigates to `/admin/products/$id` (id from the body), and the edit page shows `Product saved` once (router state or a search flag that is then removed). This joins the fixed UI text.
- **E29 (S12) [WEB].** After a 5xx (including E5's and E10's 503) from a mutation, the form shows a generic error and refetches the product; it never resubmits on its own. Mutations keep `retry: 0` as `lib/queryClient.ts` sets.
- **E30 (S2) [WEB].** The audit view wraps every rendered `from`, `to` and actor name in `<bdi>`.

### Docs

- **E31 (S12, S7, S10, residual risks) [DOCS].** ADR 0007 also states: writes are safe to retry (`PUT` carries a version, `POST` is unique by slug, so a retry gets 409), and a write reported as 5xx may have committed; `actor_name` is personal data, and erasure needs the owner role to disable the trigger, a manual, logged procedure for the cloud guide. The threat-model admin section lists these residual risks: (1) the owner DB role can drop or disable the audit triggers and update, delete or truncate the table; (2) `actor_name` is user-editable display data, only `actor_subject` is evidence, and a re-created local realm (R11) issues new subjects; (3) a demoted admin can write for up to 5 minutes, and read for up to 15 with Keycloak down (E5); (4) no rate limit on admin writes, so audit growth is unbounded, and the audit page reaches `OFFSET` 1 000 000 (admin-only cost); (5) admin request bodies are bounded only by Jackson's 100M-character string limit until slice 5; (6) the audit trace id is client-controlled (E6); (7) actor names in the append-only log cannot be erased without the owner role, so a retention and erasure procedure is needed; (8) a future audit export must neutralise CSV formula prefixes (`= + - @`, tab, CR); (9) price and stock changes race with slice 4 checkout, which must re-price and re-check stock under a lock (E4); (10) on kind and dev the seed re-activates a deactivated seed product and overwrites an admin-created product whose slug a later seed adds; (11) a write reported as 5xx may have committed, which is safe because of version and slug idempotency.

### E32: updated work split, fixed interfaces and verification

| Phase | Agent | Owns |
|---|---|---|
| 1 | API-ADMIN | `services/api/**`, now including `auth/RevalidationFilter`, `auth/ValkeyFailureFilter` (E5, E10), `web/` (E8, E9, E19) and `application.yaml` (E10). First deliverable unchanged: the contract and a final `openapi.json` |
| 1 | DOCS | `docs/adr/0007*`, `docs/threat-model.md` drafts, with E2 to E6, E9, E19, E23 and E31 |
| 2 (after the contract) | WEB | `apps/web/**` except generated files (E9, E26 to E30) |
| 2 (after the contract) | E2E | `tests/e2e/**` except `cluster/` (E2, E28) |
| 3 | DOCS | `README.md`, `tests/README.md` (new ITs incl. `AuditRollbackIT`, the audit high-water mark), `tests/manual/README.md` (add: a demoted admin's write during a Keycloak outage gets the 503 message), ADR 0007 finalised |

**Fixed interfaces, added:** the 503 problem+json for overdue admin writes (E5) and for lock timeouts with `Retry-After` (E10); schema `AdminStock` (E17); `reserved` in `stock.set` changes (E22); the plain-text patterns (E9); navigation to `/admin/products/$id` after create with `Product saved` shown there (E28); column header `Trace (unverified)` (E6); the e2e description (E2) and the setup file name `specs/admin-cleanup.setup.ts`.

**Verification, added:** API-ADMIN also runs `make api-test` with `RevalidationIT`, `OidcLoginIT` and `BackChannelLogoutIT` green after E8. The orchestrator's second `make cluster-e2e` is where E2's paging cleanup and its "none left active" assertion run against accumulated rows.
