# Load tests

k6 scenarios and helper scripts for the kind cluster. Run them through the Makefile (`make/loadtest.mk`); nothing is installed on the host.

## Targets

| Command | What it does |
|---|---|
| `make loadtest-lint` | `k6 inspect` on every `loadtest/scenarios/*.js` (no cluster needed) |
| `make loadtest` | Full run with the HPA gate. Needs `make cluster-up`. `SCENARIO=browse\|checkout\|flash-sale` runs one. `RATE` and `DURATION` tune browse. Reports in `build/reports/loadtest/<RUN_ID>/` |
| `make loadtest-cache` | Browse with the catalog cache off and on, three rounds each on two pinned API replicas |

The compose dev stack must be down (`make down`); both targets refuse to run beside it.

## Run order (`make loadtest`)

1. `obs-up`, then `deploy OVERLAY=loadtest` (enables the mint endpoint). Start the HPA sampler container (`hpa.csv`, one row per 5 s).
2. `probe-front-door` (k6): sends the valid token through the front door on odd paths (trailing slash, `%6C`, `%2F`, `//`, `/./`, upper case, `;jsessionid=x`, GET and HEAD). Any 2xx or `__Host-SESSION` cookie fails the run. The spellings Envoy normalises into `/api/loadtest` (exact, trailing slash, `%6C`, `//`, `/./`, `;jsessionid=x`, bare `/api/loadtest`) must answer exactly 404 for every method, so removing the gateway's not-found rule fails the probe (`k8s-lint` also asserts, in every non-loadtest overlay, that the single HTTPRoute `shop` in namespace `shop` has a rule matching only `PathPrefix /api/loadtest` with no method and an `ExtensionRef` filter named `not-found`; rules on other routes, a different match type or a backend named `not-found` do not count). Output: `probe.txt`.
3. Flash sale (unless another scenario was selected): `settle.sh`, `flash-setup.sql`, `db-stats` before, background DB sampling every 10 s, write `t0`, run `flash-sale`, write `t1`, `db-stats` after, `flash-verify.sh`, settle again (full run only).
4. `checkout-setup.sql`, then `checkout`.
5. `browse`.
6. `hpa-gate.sh` (only when the flash sale ran).
7. Exit trap, always (on normal exit, failure, Ctrl-C, TERM and HUP): remove sampler and DB-stat containers, `checkout-teardown.sql`, `flash-teardown.sql`, `settle.sh --warn-only`, the credential check (step 8), then `deploy OVERLAY=obs` (the mint endpoint is never left running). The trap ignores INT, TERM, HUP and PIPE while it runs, and the teardown steps and the closing deploy each run in their own session (`setsid -w`, stdin from `/dev/null`, output to `teardown.log` and `restore-obs.log` in the run directory). `make loadtest` refuses to start if `setsid` is missing. Ignoring the signals in the shell is not enough: the Docker CLI re-enables SIGINT and forwards it into its container. A separate session has no controlling terminal, and output to files means a dead `| tee` pipe or a hung-up terminal cannot fail them. Every cleanup message is non-fatal. If the final deploy fails, the run prints an error naming the live minting endpoint (NodePort 30090) and the recovery command `make deploy OVERLAY=obs`, and writes `RESTORE-FAILED` in the run directory and in `build/reports/loadtest/`; a successful restore, or the manual recovery `make deploy OVERLAY=obs` (any successful deploy of a non-`loadtest*` overlay), removes the fixed marker with a one-line notice; the run-directory marker stays as a record. A SIGKILL or host shutdown cannot be handled. `make cluster-e2e` fails while the marker, the `loadtest` HTTPRoute in `shop` or a Service on NodePort 30090 exists.
8. Credential check, run from the exit trap so failed and interrupted runs are checked too: the run exits non-zero if any file in the run directory matches `__Host-SESSION` or `XSRF-TOKEN` followed by `=`, `"`, `:` or `%`, or a `"xsrf":` key, case-insensitive (only a count is printed; k6 console output is not scanned). A leak does not skip the closing deploy. k6 summaries drop `setup_data`, which holds the minted cookies and tokens, before they are written.

`make loadtest-cache` alternates `loadtest-nocache` (off) and `loadtest-pinned` (on) for rounds 1 to 3, one directory `cache-<mode>-<round>` each. The first round runs the probe and `explain-catalog.sql` (`explain-catalog.txt`). Each run first does a separate warm-up k6 run (`browse -e DURATION=$CACHE_WARMUP`, default 30 s, reports in `<dir>/warmup`, thresholds not gating), then takes `db-stats`, runs the measured browse, takes `db-stats` again and saves `kubectl top pods`.

Transactions per request = (`xact_commit` in `db-after.txt` minus `xact_commit` in `db-before.txt`) / `http_reqs{load:shop}` count of the measured run's `browse-summary.json`. Both cover the measured run only (the warm-up ran before the snapshot). The only extra database work in the numerator is `setup()`'s catalog discovery, a handful of requests. It ends with `deploy OVERLAY=obs`, run the same way as in step 7 (own session), after the same credential check.

## Scenarios

| Scenario | Load | Thresholds |
|---|---|---|
| `probe-front-door` | One pass of path variants with the valid token | `probe_violations count==0` |
| `browse` | Anonymous, constant arrival 30 it/s for 2 m (`RATE`, `DURATION`, `WARMUP`). Visits `/`, `/api/me`, categories, product lists (pages, category, sort, some text search), product detail | `http_req_failed{load:shop}` < 1%, `http_req_duration{load:shop}` p95 < 500 ms, checks > 99% |
| `checkout` | Ramp 5 to 20 VUs over 3 m on the `lt-<RUN_ID>-1..5` products (1,000,000 units each). Add to cart, `POST /api/checkout` with a fresh `Idempotency-Key`, pay, complete on the fake PSP, poll until `PAID` (1 s, up to 20 s) | failed < 1%, checkout p95 < 1.5 s, `order_paid` > 98%, `time_to_paid` p95 < 10 s, checks > 99% |
| `flash-sale` | 100 units of `flash-<RUN_ID>`. 150 authenticated VUs buy at once; then a 3 m hold: anonymous browse at `FLASH_HOLD_BROWSE_RATE` plus 10 authenticated checkout VUs (mostly 409 sold out); the hold scenarios start `FLASH_HOLD_START` (1 m) into the run, after the burst has sold out | `flash_orders_created` and `flash_orders_paid` <= 100, failed < 1% |

Every load scenario (`browse`, `checkout`, `flash-sale`) also has the threshold `rate_limited count==0`. The counter (`lib/checks.js`, `rateLimitThreshold` and `countRateLimit`) goes up on any 429 from a shop request outside `setup()`, so a run fails if the rate limits were not raised. The `loadtest*` overlays raise them: the API takes `SHOP_RATE_LIMIT_SCALE` (1000 in the loadtest component) and the Envoy rules are patched to at least 100 000 per unit. All k6 traffic shares one source IP and VUs check out faster than a person, so production values would measure the limiter, not the app. Mint requests are outside this counter (the mint limiter has its own `Retry-After` handling).

Each scenario writes `<scenario>-summary.json` and `<scenario>-summary.md` to `/reports`. Thresholds filter on the tag `load:shop`, which mint requests do not carry, so minting is outside latency and failure thresholds.

## Scripts

POSIX `sh`, run in the `k8s-tools` image with the repo at `/work` (read-only) and the run directory at `/reports`. They reach the cluster through `kubectl`.

| Script | Arguments | Purpose |
|---|---|---|
| `sql.sh` | `<file.sql> [name=value ...]` | Runs `loadtest/sql/<file>` as `postgres` in the CNPG primary; variables become `psql -v` |
| `db-stats.sh` | `<output file>` | Timestamp, `pg_stat_database` counters, connections, `max_connections` |
| `hpa-sampler.sh` | `<csv>` | Appends `time,epoch,current,desired,cpu_percent,ready_api_pods` every 5 s until killed |
| `settle.sh` | `<csv> [--warn-only]` | Waits until HPA desired = current = min and CPU is under target for 60 s; timeout 6 min (exit 1, or 0 with `--warn-only`). Appends a `settled` row |
| `hpa-gate.sh` | `<report dir>` | Reads `hpa.csv`, `t0`, `t1`. Passes only if desired > min after t0, a `SuccessfulRescale` event is at or after t0, and ready API pods > min within t0 to t1. Writes `hpa-gate.txt` |
| `flash-verify.sh` | `<report dir> <run id>` | Waits up to 60 s for no `PENDING_PAYMENT` flash orders, then prints one PASS/FAIL line per check to `flash-verify.txt`; exit 1 on any FAIL or no PASS |

`/reports/t0` and `/reports/t1` are epoch seconds written by the Makefile around the flash sale.

Flash checks: sold <= 100; sold equals the sum of `CONSUMED` reservations; sold equals 100 minus `on_hand`. Sold is the quantity on `PAID` orders.

## Token

`SHOP_LOADTEST_TOKEN` is read from the secrets file into the Make recipe's shell and handed to k6 as an environment variable by name. It is never put on a command line and never echoed; scenarios must not log it. k6 gets no other secret. k6 mints all sessions sequentially in `setup()`, honours `Retry-After`, and re-mints old sessions (staggered by VU id) in long phases.

## Cleanup

Test products are deactivated, not deleted, by the exit trap, so an interrupted run leaves no active product. Setup deactivates leftover `lt-%` and `flash-%` products first. Seeded stock is never touched.

## Known limits

- The `loadtest` category stays visible in the category list after teardown (the list is unfiltered). Run `make cluster-e2e` after a load test to confirm count-based specs still pass.
- `FLASH_HOLD_BROWSE_RATE` (default 60 it/s, `FLASH_HOLD_RATE` env) is a first guess for about twice the HPA CPU target. Calibrate on real runs from `hpa.csv`, then record it in `docs/performance.md`. `make loadtest FLASH_HOLD_RATE=<n>` passes it to k6 (unset keeps the default). Calibration (2026-11-24, kind on WSL2): the default 60 it/s took the HPA from 2 to 4 replicas with the gate passing, so it is kept.
- Hold-phase length parsing assumes `FLASH_HOLD_DURATION` ends in `m`.
- `settle.sh`, `hpa-sampler.sh` and `hpa-gate.sh` were checked under the image's busybox 1.37 against a stub `kubectl`, not against a live cluster.

## Profile guards

`make k8s-lint` (`k8s-loadtest-guard`) renders every overlay not named `loadtest*` and runs `make/forbid-loadtest-env.awk` over the whole output. It rejects: the word `loadtest` (case-insensitive) except the two base Gateway lines; a second `value: /api/loadtest` line; `$(...)` other than `$(POD_NAME)`; any `spring.application.json` spelling (`.`, `_` or `-`, any case); any `\u` escape; `${` in a `value:` line (only there: Keycloak args and realm JSON carry legitimate `${...}`); `binaryData`; and any `envFrom` (no overlay uses one, so the allowlist is empty). `make compose-lint` applies the word (any case), `\u` and `spring.application.json` checks to the rendered compose configs and fails if `docker compose config` fails. These catch accidental and plain spellings. They are not a defence against someone who obfuscates on purpose; the token check, `LoadtestGuard` and the CI `cluster-e2e` 404 test stand behind them.
