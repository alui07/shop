# Slice 4b implementation brief: payment, webhook, outbox, email, self-registration

Branch: `slice-4b-payment` from `main` at `1efd101` (PR #8 merged 4a). Read first: `CLAUDE.md`, `master-plan.md`, `slice-4-brief.md` (the body's **[4b]** parts, then amendments F1, F2, F4, F5, F25 to F47 and F49), ADR 0006, 0007 and 0008, `docs/threat-model.md`, `tests/README.md`.

This brief does not repeat the slice 4 design. It reconciles it with the 4a code as merged and lists what 4b adds on top. Precedence: this file's amendments, then this file's body, then `slice-4-brief.md` amendments, then that brief's body.

Standing rules for every agent: every tool runs in a container through the `Makefile`; nothing is installed on the host. If Docker fails with a permission error, prefix with `sg docker -c "..."`. No git commands except read-only `git status`, `git diff` and `git log`. Coverage gates are 100% (API line and branch; web lines, branches, functions, statements); the only exclusions are `apps/web/src/routeTree.gen.ts` and `apps/web/src/components/ui/**`; never add one, stop and ask. Never hand-edit `openapi.json`, `schema.d.ts` or `routeTree.gen.ts`; run `make generate`. Pins go in `deploy/versions.mk`; pnpm's 7-day release-age gate stays on. Files under 500 lines; reports under `build/`, nothing in the repo root. Never commit or print secrets. High-risk slice: two reviewers (general, security-only).

## Goal

From `slice-4-brief.md` Goal, the 4b lines, with F32's wording: a customer pays on the fake provider page; the signed webhook marks the order paid (or late-paid, or refunded per F25); one confirmation email reaches Mailpit through the Modulith event registry, and a Mailpit outage of up to about a day delays it, beyond that it is parked in a dead-letter table and logged; customers can register with email verification. `make ci`, `make cluster-down cluster-up deploy cluster-e2e` and a second `cluster-e2e` pass. ADR 0009, the threat model's webhook section, README and test docs are updated.

## Versions (re-read 2026-11-20)

No change from `slice-4-brief.md`. Spring Modulith stays `2.1.2` (2.1.3 and 2.2.0 are from 2026-11-19), Spring Boot stays `4.1.2` (4.1.3 and 4.2.0 from 2026-11-19), Mailpit stays `v1.33.1` (v1.34.0 from 2026-11-17). All are under the 7-day gate; Renovate raises them after 4b. Do not bump inside this slice. New dependencies: `spring-modulith-starter-jdbc` (Modulith BOM), `spring-boot-starter-mail` (Boot managed), `org.awaitility:awaitility` (test, Boot managed). Check with `dependency:tree` that no `com.fasterxml.jackson.core:jackson-databind` arrives, and that `make api-build` (offline image build since PR #7) resolves them.

## Owner decisions needed

| # | Decision | Recommendation |
|---|---|---|
| D28 | Design challenge for 4b | **One fresh Opus challenger with a security and failure-mode lens, given only this brief, `slice-4-brief.md` and the code.** Slice 4 was challenged twice (G and S, 2026-11-19) and every finding is folded in; what is new here is the reconciliation (R1 to R14), which touches trust boundaries (R2, R3, R7) and async correctness (R1). Alternative: the full pair again (general and security), which mostly re-reads already challenged design. Both reviewers on the diff stay as the process requires |
| D29 | Scope of the PR | **Keep 4b as one PR.** Registration needs Mailpit in every stack anyway, and the payment e2e needs the email path. Alternative: move self-registration (F39 realm part, `registration.spec.ts`) to a 4c; saves about a tenth of the diff and one Keycloak risk from this review |

## Reconciliation with the 4a code

Each item names the code as merged and what 4b does. Owners in brackets.

- **R1. `@EnableAsync` must not be conditional [API-PAYMENT].** `web/AsyncAndSchedulingConfiguration.java` is `@ConditionalOnProperty(shop.jobs.enabled, matchIfMissing = true)`, and `@IntegrationTest` sets `shop.jobs.enabled=false`. F18's "4b adds `@EnableAsync` there" would switch async off in every IT, so `@ApplicationModuleListener`s would run on the request thread and tests would pass for the wrong reason (`slice-4-brief.md` Known traps). Split it: `web.AsyncConfiguration` (unconditional `@EnableAsync`, executor properties from F34 and F18) and `web.SchedulingConfiguration` (the existing conditional `@EnableScheduling`); rename the existing test to match. Add a test that the async config is present with `shop.jobs.enabled=false`. Verify in the Modulith 2.1.2 sources whether its staleness monitor needs `@EnableScheduling`; if it does, F18's note stands (ITs call the jobs directly).
- **R2. Body-limit filter exemption for the webhook [API-PAYMENT].** `web/RequestBodyLimitFilter.java` caps every `/api/**` body except `/api/admin/**` at 16 KiB and refuses non-JSON with 415 before reading. A webhook body between 16 and 64 KiB would get 413, breaking F30. `shouldNotFilter` also skips the exact URI `/api/payments/webhook` (string equality on `getRequestURI()`, no normalisation); `WebhookController` enforces its own 64 KiB and 415 (F30). Path variants (`/api/payments/webhook/`, `//`, case) stay under the 16 KiB cap and then reach the main chain's `denyAll`. `BodyLimitIT` adds: a correctly signed 20 KiB webhook gets 200; a 20 KiB body to a variant path never gets 200.
- **R3. Two security chains [API-PAYMENT].** The existing `SecurityConfiguration.securityFilterChain` has no `securityMatcher` and no `@Order`, so it is the catch-all at lowest precedence. The new `WebhookSecurityConfiguration` chain gets `@Order(1)` and the exact `PathPatternRequestMatcher` for `POST /api/payments/webhook`. The main chain adds `/api/fake-psp/**` to the `hasRole("CUSTOMER")` rule (`/api/orders/{id}/payment` is already under `/api/orders/**`). `ValkeyFailureFilter` and Spring Session's `SessionRepositoryFilter` are servlet filters outside both chains, so they also see webhook requests: F31's test (Valkey unreachable plus a session cookie, still 200) is the real guard. If it fails, the webhook path must bypass session resolution (wrap the request so `getSession` is never called) and the fix is recorded as an amendment. `AuthorizationMatrixIT` and `CsrfIT` get rows for the four new routes.
- **R4. `OrderStateMachine` carries a stock effect [API-PAYMENT].** It maps `(status, trigger) -> status`, and its Javadoc and `OrderService.cancel` assume every transition releases stock. 4b changes the value to `Transition(OrderStatus next, StockEffect effect)` with `StockEffect` in `NONE`, `RELEASE`, `CONSUME`, `TAKE_LATE`. Triggers encode the facts F25 decides on, computed by the caller under the locks, so the table stays pure: `CANCEL`, `EXPIRE`, `PAY_IN_TIME`, `PAY_LATE_TAKEABLE`, `PAY_LATE_NOT_TAKEABLE`, `PAY_MISMATCH`, `PAY_FAILED`, `PAY_OTHER_PAYMENT`, `REFUND_DONE`, `REFUND_DONE_PARTIAL`. Rows: F25 (a) to (h) plus 4a's two. `cancel` and `OrderExpiry` apply the row's effect instead of calling `release` unconditionally. `OrderStateMachineTest` enumerates every `(status, trigger)` pair. ADR 0009 replaces ADR 0008's deviation line on the table's shape.
- **R5. Order statuses and contract [API-PAYMENT, WEB].** Java `OrderStatus` has three values; add `PAID`, `REFUND_PENDING`, `REFUNDED` (V7's CHECK already allows them). `presentedAt` still only rewrites an overdue `PENDING_PAYMENT`. `Order` gains `paymentStatus` (nullable enum `CREATED`, `SUCCEEDED`, `FAILED`, `REFUND_PENDING`, `REFUNDED`) and `latePayment` (boolean); `OrderSummary` gains only the new status values. The web's `STATUS_LABELS` is a `Record` over the generated enum, so `make web-lint` fails until the three labels from `slice-4-brief.md` (`Paid`, `Refund pending`, `Refunded`) are added: that is intended.
- **R6. Late success on an order the web shows as expired [WEB].** 4a's `effectiveStatus` shows an overdue `PENDING_PAYMENT` as `Expired` and hides actions. Under F25 (b) such an order can still become `PAID` when the success arrives within the late window. The fake page navigates to `returnUrl` with router state `{ fromPayment: true }`; while that state is set and the server status is `PENDING_PAYMENT`, the order view polls every second for up to 20 s and shows `Payment is being confirmed.` instead of `Expired`; after 20 s it falls back to 4a's presentation. A reload drops the state and shows 4a's presentation; the server's next status wins either way. `Pay now` stays hidden once `expiresAt` has passed (F21).
- **R7. Order access without a user [API-PAYMENT].** `OrderService` is `@PreAuthorize("hasRole('CUSTOMER')")` at class level and every lock is `lockOwned(id, subject)` (F16). The webhook and listeners run with no user. `OrderPayments` (public, named interface `order::payments`) and `OrderSummaries` (`order::summaries`, F38) are separate beans without the class-level guard. `payable(sub, orderId)` keeps `@PreAuthorize("hasRole('CUSTOMER')")` and uses `lockOwned`. The other `OrderPayments` methods use a new `OrderRepository.lockById(id)` that is package-private and called only from `OrderPayments`; `ModuleRulesTest` asserts `payment` depends only on `order::payments` and `notification` only on `order::summaries`. Update `order/package-info.java` named interfaces and `payment`'s and `notification`'s `allowedDependencies` (F38 says `payment` allows `order`; make it `order::payments`).
- **R8. `StockReservations` gains `consume` and `takeLate` [API-PAYMENT].** Both `Propagation.MANDATORY`, ascending product id, each stock row at most once (F6). `consume(orderId)`: sum the order's `ACTIVE` reservations per product, `UPDATE stock SET on_hand = on_hand - q, reserved = reserved - q, version = version + 1`, mark them `CONSUMED`. `takeLate(orderId, lines)`: `SELECT ... FROM stock WHERE product_id IN (...) ORDER BY product_id FOR UPDATE`, check `on_hand - reserved >= q` for every line, then `on_hand -= q, version + 1` and insert `CONSUMED` reservation rows (a record of what was taken; the invariant sums only `ACTIVE`); returns false and changes nothing when any line is short. V3's `CHECK (reserved <= on_hand)` holds in both. `MandatoryTransactionIT` and the invariant assertion cover them; `LatePaymentIT` asserts `version` bumps.
- **R9. Migrations [API-PAYMENT].** 4a used V6 to V8, so V9 and V10 keep their numbers. V9: `payment` (with `received_amount_minor`, partial unique `(order_id) WHERE status = 'CREATED'`, `order_id REFERENCES orders`), `payment_webhook_event`, `payment_webhook_unmatched`, `fake_psp_payment` (with `event_id UNIQUE`, `expires_at`), `order_email` (with `status`, `claimed_at`, `sent_at`), and `ALTER TABLE orders ADD COLUMN payment_status text CHECK (...)`, `ADD COLUMN paid_payment_id uuid REFERENCES payment (id)`. V10: `event_publication` (Modulith 2.1 schema) and `event_publication_dead_letter`. Both start with `SET LOCAL lock_timeout = '5s'`. `CatalogFixtures.clear` truncates every new table (F37).
- **R10. Request log masking [API-PAYMENT].** `web/ApiRequestFilter.java` logs `getRequestURI()`; mask the last segment of `/api/fake-psp/payments/*` (F40) there, and add a test that the token never appears in the log line.
- **R11. Makefile and stacks [orchestrator, PLATFORM].** `MVN_IT_DOCKER` (Makefile line 43) lacks `-e MAILPIT_IMAGE`; `MAILPIT_IMAGE` is already exported (F43, orchestrator, 4b.0). `make secrets` appends missing keys without rotating others, so adding `SHOP_PAYMENT_WEBHOOK_SECRET` to `SECRET_KEYS` needs one `make secrets` on existing checkouts (README note; check the CI workflow creates it too). `apply_secrets` gains Secret `shop-payment`. The dev compose already has `mailpit` (host 8025); add the api and keycloak wiring. `tests/e2e/compose.yaml` has no `mailpit`: add it with `E2E_MAILPIT_URL=http://mailpit:8025` for the runner.
- **R12. Keycloak specs [E2E].** `slice-4-brief.md` says `keycloak.spec.ts` "no longer expects registration to be refused"; no current spec asserts that. The client-registration 404 check in `keycloak.spec.ts` stays as it is. Add instead: the sign-in page shows a `Register` link, plus F39's reset-credentials checks.
- **R13. Kind database wipe [orchestrator].** F4's new host port means one `make cluster-down cluster-up`, which drops the kind database (orders from 4a included). Record it in STATUS when it happens; the e2e specs never depend on prior data.
- **R14. Single API replica [PLATFORM].** `deploy/k8s/base/api.yaml` runs one replica, so multi-replica behaviour (advisory-locked retry job, `SKIP LOCKED`) is proven only in ITs. No change; ADR 0009 says so.

## What 4b builds (index; the design is in `slice-4-brief.md`)

| Area | Where it is specified |
|---|---|
| Modules `payment`, `fakepsp`, `notification`; `order::payments`, `order::summaries` | body Modules table; F38; R7 |
| Endpoints (payment start, fake PSP read and complete, webhook) | body "Endpoints [4b]"; F27, F28, F29 |
| Order and payment state machines | body state table (4b rows replaced by F25); F25, F26; R4 |
| Webhook format, verifier, chain | body "Webhook", "Webhook chain"; F30, F31; R2, R3 |
| Fake provider | body "Fake provider"; F28, F29, F30, F36 |
| Outbox, retry job, dead letter | body "Outbox"; F32, F33, F34, F35, F37; R1 |
| Email | body "Email"; F34, F39, F40 |
| Keycloak registration | body "Keycloak"; F39; R12 |
| Web: `Pay now`, `/fake-psp/$token`, polling, URL checks | body "Web" [4b]; F21, F28, F41; R5, R6 |
| Platform: Mailpit in `overlays/local`, listener `mail`, NetworkPolicies | body "Platform"; F4, F44; R11 |
| E2E: projects `payment`, `registration.spec.ts` | body "E2E" [4b]; F20, F39, F42; R12 |
| Docs: ADR 0009, threat model, README, tests docs | body "Docs" [4b]; F5, F45, F46, F47 |
| Tests | body "Tests" [4b]; F25 to F37; R1 to R4, R8, R10 |

## Work split (F49, with the reconciliation)

| Phase | Agent | Owns |
|---|---|---|
| 4b.0 | orchestrator | `MVN_IT_DOCKER` line (F43, R11) |
| 4b.1 | API-PAYMENT (Sonnet) | `services/api/**`, `deploy/keycloak/shop-realm.json`. Contract first: records, controllers, `OrderStatus` and `Order` changes, then `make api-spec`; write `build/reports/slice-4b-contract-ready.md` |
| 4b.1 | PLATFORM (Sonnet) | `Makefile` except the 4b.0 line, `deploy/**` except the realm, `tests/e2e/compose.yaml`, `tests/e2e/cluster/**`, `.github/workflows/**` if needed |
| 4b.1 | DOCS (Sonnet) | ADR 0009 and threat-model webhook drafts (F45, F46), marking code-dependent claims "(verify after implementation)" |
| 4b.2 | WEB (Sonnet) | `apps/web/**` except generated files, after the contract is ready (R5, R6, F28 text, F41) |
| 4b.2 | E2E (Sonnet) | `tests/e2e/**` except `compose.yaml` and `cluster/` (F20, F39, F42, R12) |
| 4b.3 | DOCS (Sonnet) | README, `tests/README.md`, `tests/manual/README.md`, ADR 0009 final with Deviations (F47) |

Fixed interfaces: those of `slice-4-brief.md` F49, plus `StockEffect` and the trigger names (R4), `Order.paymentStatus` and `Order.latePayment` (R5), router state `fromPayment` (R6), named interface `order::payments` (R7), and the exact webhook path exemption (R2). Changes go through the orchestrator and this file's amendments.

## Verification

Per agent, as `slice-4-brief.md` "Verification per agent" and F49, plus: API-PAYMENT runs `OutboxIT` with Mailpit paused and hung, `WebhookIT` with Valkey unreachable, `BodyLimitIT`'s webhook rows, and asserts the listener thread is not the test thread (R1). PLATFORM renders `base` alone (no Mailpit object, `mail` listener or SMTP env), runs `make cluster-up` on the existing cluster to see F4's warning, then `make cluster-down cluster-up deploy cluster-smoke` and the NetworkPolicy script. WEB `make web-lint web-test web-build`. E2E `make e2e-lint`, then `make e2e` twice once API and WEB are in.

Orchestrator integration: `make generate` (no diff), `make lint`, `make ci`, `make cluster-down cluster-up deploy cluster-e2e`, a second `make cluster-e2e`, a second `make deploy` (no restarts), `make dev` with a manual pay, decline, email, Mailpit stopped then started, and register-and-verify pass. Record V9 and V10 applying and the kind wipe (R13) in STATUS.

Review: a general reviewer and a security-only reviewer on the same diff, each fresh and without the orchestrator's conclusions; both must approve.

## Top risks (in addition to `slice-4-brief.md`)

1. Async silently off in tests (R1): the thread assertion in `OutboxIT` and the config test.
2. The webhook reachable with a weaker or stronger stack than intended (R2, R3): exact-path exemptions in two places, tested from both sides.
3. A transition that moves stock wrongly (R4, R8): the effect is part of the table, enumerated by the unit test, and every concurrency IT checks `reserved = sum(active reservations)`.
4. Size: 4b is the largest diff so far. Contract first, and the API agent reports at the contract and at the end so WEB and E2E start early.

## Amendments

Accepted from the design challenge (one Opus challenger, security and failure-mode lens, D28), 2026-11-20. Report: `build/reports/slice-4b-challenge.md` (1 blocker, 10 should-fix, 4 nits). All 15 findings accepted. Where an amendment contradicts R1 to R14 or `slice-4-brief.md`, the amendment wins: notably C1 adds `payment.redirect_url` and `merchant_reference UNIQUE` to R9, C2 changes how R4's `PAY_LATE_TAKEABLE` is applied, C3 widens R6, C4 replaces R10's masking rule, C9 replaces R7's package-private `lockById` placement and `Money` across modules, C12 replaces R1's config test, C13 corrects R2 and F31 on encoded variants.

**C1 [4b: API-PAYMENT].**
- `PaymentProvider.createPayment(paymentId, amount, currency, returnPath)` is idempotent by `paymentId`. The fake enforces this with `fake_psp_payment.merchant_reference UNIQUE` and `INSERT ... ON CONFLICT (merchant_reference) DO NOTHING`, then returns the existing session.
- V9 adds `payment.redirect_url text NULL`, set together with `provider_payment_id`.
- Payment start returns the stored `redirect_url` when the open `CREATED` payment has one. When it has none (another start is in flight, or one crashed), it calls `createPayment` with the same `paymentId`, which is safe because the call is idempotent, and stores the result with `UPDATE payment SET provider_payment_id = :p, redirect_url = :u WHERE id = :id AND (provider_payment_id IS NULL OR provider_payment_id = :p)`.
- Webhook: when a payment is found by `merchantReference` but its stored `provider_payment_id` is non-null and differs from `data.providerPaymentId`, the event is a separate capture. Treat it as F25 (e) or (f): order unchanged, `RefundRequested` for that provider payment id. Never a no-op.
- Tests:
  - `PaymentRaceIT`: two concurrent starts give the same `redirectUrl` and exactly one `fake_psp_payment` row.
  - `PaymentIT`: `createPayment` throws once (test double), then a second start succeeds and returns a session.
  - `WebhookIT`: a signed success with a matching `merchantReference` but a different `providerPaymentId` leads to a refund, and the order and stock are unchanged.

**C2 [4b: API-PAYMENT].**
- `StockEffect` application returns a boolean, and only `TAKE_LATE` can return false.
- The order transition code: (1) computes the trigger from window and activity, (2) applies the effect, (3) when `TAKE_LATE` returns false, re-looks up `(EXPIRED, PAY_LATE_NOT_TAKEABLE)` and applies that row (effect `NONE`), (4) only then writes the status. No status is written before the effect succeeds.
- `OrderStateMachineTest` asserts that `TAKE_LATE` appears only in rows whose fallback row exists.
- `LatePaymentIT` adds: `EXPIRED` order, within the window, products active, one line short, gives `REFUND_PENDING`, every `stock` row and `version` is unchanged, and the invariant holds.

**C3 [4b: WEB].** R6 polls while `fromPayment` is set and the returned status is `PENDING_PAYMENT` **or `EXPIRED`**, every second for up to 20 s. During that time it shows `Payment is being confirmed.`, then falls back to 4a's presentation. Web unit tests cover returned `EXPIRED` + `fromPayment` (polls, then shows `Paid` with the late note when the next response is `PAID`), returned `EXPIRED` without the state (no polling), and the 20 s timeout.

**C4 [4b: API-PAYMENT].** `ApiRequestFilter` replaces the segment right after `/api/fake-psp/payments/`, whatever follows it, with `***`. Tests (`OutputCaptureExtension`):
- `GET /api/fake-psp/payments/{token}`, `POST .../{token}/complete`, and a request with a trailing slash all log the masked form.
- The token appears in none of the captured output.

**C5 [4b: API-PAYMENT].**
- `WebhookSender` builds the body from stored fields only (`event_id`, `created` from `complete`, data), so the body is byte-identical across attempts.
- It signs at each attempt with `t = clock.instant()` at send time. Nothing signed is stored.
- `FakePspIT`: the first delivery fails, `MutableClock` advances 10 minutes, the retry gets 200, the order is paid once, and the `Shop-Signature` `t` differs between the two attempts.

**C6 [4b: API-PAYMENT].**
- When the send fails, the mailer releases its claim in a short transaction (`status = 'FAILED'`, `claimed_at = NULL`) before rethrowing. A retry may claim a `FAILED` row, or a `SENDING` row older than 1 minute (a crash between send and commit).
- A `SENDING` row younger than 1 minute makes the listener throw a `RetryLaterException`, which leaves the publication incomplete. It never returns normally.
- `OutboxIT`:
  - Mailpit paused, the first attempt fails, `EventRetryJob.runOnce()` runs immediately after unpausing (no backoff, by moving the clock), and exactly one email arrives.
  - A second listener invocation while the row is fresh `SENDING` leaves the publication in `event_publication`.

**C7 [4b: API-PAYMENT].**
- Listener failures are rethrown as `ListenerFailedException(String safeMessage)` with **no cause**. The message holds the order id and the cause's class name only.
- `web.AsyncConfiguration` also registers an `AsyncUncaughtExceptionHandler` that logs `method + exception class + getMessage()` at WARN, with no stack trace.
- IT using `OutputCaptureExtension` and `@MockitoBean JavaMailSender` throwing `MailSendException("... <victim@example.test>")`: after Awaitility sees the publication `FAILED`, the captured output does not contain `victim@example.test`.

**C8 [4b: API-PAYMENT].** `PaymentProperties.webhookSecret` is `@NotBlank` only. Its length is checked in the record's compact constructor, which throws `IllegalStateException("shop.payment.webhook-secret must be at least 32 characters")` with no value in the message. `toString` redacts it. A test starts an `ApplicationContextRunner` with a 20-character secret, asserts the failure, and asserts that neither the failure's message chain nor the captured output contains the value.

**C9 [4b: API-PAYMENT].**
- `OrderPayments` and `OrderSummaries` stay in `dev.shop.api.order`, with **type-level** `@NamedInterface("payments")` and `@NamedInterface("summaries")`.
- `RefundRequested` is annotated into `payments`, and `OrderPaid` into `summaries` (type-level).
- Cross-module amounts are `long amountMinor, String currency` (a `PaymentAmount` record in `order::payments`), never `Money`.
- `createPayment` takes `returnPath`.
- `payment` allows `order::payments`, `customer`, `web`; `fakepsp` allows `payment`, `web`; `notification` allows `order::summaries`, `web`.
- `ModuleRulesTest` adds two checks:
  - An ArchUnit rule that `OrderRepository.lockById` is called only from `OrderPayments`.
  - A deliberate `notification` → `OrderPayments` reference (a test-class fixture like `ReservationPeeker`) is reported as a violation.

**C10 [4b: API-PAYMENT].** `EventRetryJob` first counts `FAILED` rows (`SELECT count(*) ... WHERE status = 'FAILED'`, which stays bounded because the dead-letter move runs first). It passes `withBatchSize(max(1, count))` and a filter that admits a publication only when its backoff has elapsed **and** fewer than 200 have been admitted in this run (a counter in the predicate). `OutboxIT` adds: 250 failed publications inside their backoff, plus one newer publication that is due; one `runOnce()` resubmits the newer one.

**C11 [4b: API-PAYMENT].** `OutboxIT` adds a recovery test:
1. Insert a `PUBLISHED` `OrderPaid` publication directly, as a crash would leave it.
2. Advance `MutableClock` 11 minutes and call `EventPublicationRegistry.markStalePublicationsFailed(stalenessProperties)` with the bound `StalenessProperties` bean.
3. Run `EventRetryJob.runOnce()`. Exactly one email arrives.

A context test asserts that the bound `StalenessProperties` monitors `PUBLISHED`, `PROCESSING` and `RESUBMITTED` with the configured durations. ADR 0009 states that recovery after a restart or a rejected task takes up to `staleness.published` plus the first backoff step.

**C12 [4b: API-PAYMENT].** R1 keeps the split. Its test asserts that the listener's thread name starts with the `applicationTaskExecutor` prefix (`task-`), in both a jobs-off and a jobs-on context, instead of only asserting that the config bean exists. ADR 0009 records that Modulith enables async on its own, and that the staleness monitor needs scheduling.

**C13 [4b: API-PAYMENT].**
- `shouldNotFilter` exempts only `POST` with the exact URI.
- The webhook's 413 and 415 responses set `Connection: close`.
- `BodyLimitIT` adds: `PUT /api/payments/webhook` with 20 KiB gives 413 from the filter.
- `WebhookIT` adds: `POST /api/payments/%77ebhook` signed, 1 KiB, gives 200 (documented as reaching the webhook); the same with 20 KiB gives 413.
- Correct R2 and F31's wording.

**C14 [orchestrator].** The challenger read the Makefile after the orchestrator had done 4b.0 (`-e MAILPIT_IMAGE` in `MVN_IT_DOCKER`, `Makefile:44`). 4b.0 is done; PLATFORM's ownership of the Makefile has no excluded line from now on.

**C15 [4b: API-PAYMENT].** `MaintenanceJob` also deletes `payment_webhook_event` and `payment_webhook_unmatched` rows older than 7 days, in batches like the idempotency keys. `MaintenanceIT` covers it. Threat model R37 lists the table.

Orchestrator decisions during 4b.1 (2026-11-20):

**C16 [4b: API-PAYMENT].** `fakepsp` keeps C9's dependencies (`payment`, `web`) and does not use `catalog::pricing`. `FakePspPayment` carries `amountMinor` (long) and `currency` (string) instead of `Money`; WEB formats them.

**C17 [4b: API-PAYMENT, WEB].** `FakePspPayment` gains `expiresAt` (the session's expiry, F28), so the fake page can tell the shopper when the session ends. Additive; no other contract change.

Recorded from API-PAYMENT implementation (2026-11-20, `build/reports/slice-4b-api.md`):

**C18 [4b: API-PAYMENT] (R3 fallback).** With Valkey down and a session cookie, the webhook answered 503 through four session reads. The webhook chain disables `sessionManagement` and anonymous authentication as well as using the stateless policy; `spring.mvc.publish-request-handled-events=false`; a no-op `FlashMapManager` (`NoFlashMapManagerConfiguration`). The last two also remove a Valkey read from every request that carries a session cookie. `WebhookValkeyIT` is the guard.

**C19 [4b: API-PAYMENT] (R7, C9).** `OrderPayments` gains `lock(orderId)` and `paymentCreated(orderId)`; `refunded(orderId, paymentId, otherMoneyHeld)` takes whether another payment still holds money (the order module cannot see payments); `payable` takes the verified email.

**C20 [4b: API-PAYMENT] (F28).** `PaymentProvider.createPayment` takes `holdExpiresAt`, so the fake sets its session expiry to the order's expiry plus the grace.

**C21 [4b: API-PAYMENT] (C15).** The webhook-table purge is `payment.PaymentMaintenanceJob` (covered by `PaymentMaintenanceIT`), not `order.MaintenanceJob`, because `order` may not depend on `payment`.

**C22 [4b: API-PAYMENT] (C1).** A signed success whose `providerPaymentId` differs from the stored one creates a new `payment` row in `REFUND_PENDING`, and `payment` publishes `RefundRequested` itself; order and stock are unchanged. F27's unique-violation fallback and F26's ownership re-check are not implemented: the order lock serialises starts and `payment.order_id` never changes, so neither path is reachable.

**C23 [4b: API-PAYMENT] (tests).** `ReservationExpiryIT.twoJobsRunningTogether...` and `StockRaceIT.expiryBatches...NeverDeadlock` assumed two `SKIP LOCKED` jobs drain everything in one race; by design skipped rows wait for the next run. Both now run the job once more before asserting.

**C24 [4b: API-PAYMENT, E2E] (F39, integration 2026-11-21).** Keycloak 26.8 hides the password fields on the registration form while `verifyEmail` is on: the user sets the password after following the verification link. The realm keeps that default. Showing the fields needs an authenticator config on the registration flow, and supplying any `authenticationFlows` in the import drops every built-in flow, so the realm would have to carry all 21 (95 to 799 lines, and a copy that drifts with each Keycloak bump). The default is also safer: registering someone else's address cannot preset its password. `registration.spec.ts` follows the default sequence.
