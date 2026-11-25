# Performance

Status: measured 2026-11-24 (slice 5b, runs `r1-1`, `r1-2`, `r1-cache`). Numbers come from `build/reports/loadtest/<RUN_ID>/`. A cell reading "not measured" was not captured in these runs. In `r1-cache` the measured-run files sit under `cache-*/warmup/` because of a recipe bug (a clobbered directory variable, since fixed), and the warm-up summaries were lost; the numbers are from the measured runs. Raw k6 summaries stay under `build/reports/loadtest/` (git-ignored).

These are one-host numbers: a single kind node, the load generator and the observability stack on the same machine. They show whether the shop holds together under load and where the first bottleneck is. They do not predict a cloud deployment.

## What is and is not measured

- **No rate limiting (D38).** Rate limits, gateway body limits and the Postgres `statement_timeout` are slice 5c. Every run here is without them, so a result describes the unprotected system. The only limiter is the per-pod mint limiter (10 a second), which applies to session minting only.
- **No Keycloak on the load path.** k6 mints customer sessions under the `loadtest` profile (ADR 0011). The login flow and token refresh are not exercised; a minted session ends at its first revalidation (5 minutes), so k6 re-mints. Mint requests carry no `load:shop` tag and are outside every latency and failure threshold.
- **Mail is real load (C25).** Minted users have a verified `@loadtest.invalid` address, so every paid order queues a confirmation mail through the outbox to Mailpit. The checkout and flash-sale numbers include that work.
- **The cache is measured, not assumed.** `make loadtest-cache` compares cache off with cache on (ADR 0012). `make loadtest` runs with the cache on (the `loadtest` overlay).
- **Tracing is sampled at 0.1** in the `loadtest` overlays so Tempo and the collector are not the bottleneck; cache-off and cache-on runs use the same telemetry settings.

## Host

| Fact | Value |
|---|---|
| Docker host memory (WSL2) | 15.6 GiB (no `.wslconfig`; WSL's default of half the Windows host's memory applies) |
| CPUs | 12 |
| cgroup | v1 (the kind node runs kubelet 1.36 with `failCgroupV1: false`; CI is cgroup v2 and runs no load tests) |
| Platform | WSL2 on Windows, Docker Desktop, kind (one node, `shop-control-plane`) |
| Date of runs | 2026-11-24 (PST; 2026-11-25 UTC) |
| Commit | `d69cd6f` plus the uncommitted slice 5b tree |
| k6 | `grafana/k6` 2.4.0 (pinned in `deploy/versions.mk`), in a container on the `kind` network, outside the node |

Memory budget at the design stage (C19): requests about 5 GiB at four API pods; limits about 9.8 GiB in the cluster plus about 0.5 GiB for k6. `make loadtest` refuses to start while the compose dev stack runs.

| Measured peak | Value |
|---|---|
| `docker stats shop-control-plane` memory | 6.3 GiB peak |
| `docker stats` CPU of the node | 570% peak (about 5.7 of 12 CPUs) |
| k6 container memory (flash sale) | not measured (k6 containers are unnamed, so the sampler could not single them out) |

## Settings under test

| Setting | Value |
|---|---|
| API | HPA `api`: 2 to 4 replicas, 60% of a 250m CPU request; scale-up stabilisation 60 s, scale-down 120 s; limits 2 CPUs, 768 Mi |
| API connection pool | Hikari `maximum-pool-size` 15, `minimum-idle` 5 |
| Postgres (CNPG) | `max_connections` 100, no other parameters; memory limit 512 Mi |
| Valkey | `maxmemory` 96 MB, `volatile-lru`; CPU limit raised to 1 in the `loadtest` overlays |
| Catalog cache | Caffeine, `maximumSize=2000,expireAfterWrite=30s`, three caches; text search not cached |
| Sampling | `MANAGEMENT_TRACING_SAMPLING_PROBABILITY=0.1` |
| Flash-sale hold (C13) | starts `FLASH_HOLD_START` = 1 m after the scenario starts so the 150-VU burst has sold out first (confirmed: all 100 flash orders were created 12 to 17 s after t0 and paid by 19 s, where t0 includes k6's setup). Browse rate: 60 it/s is the first guess in `loadtest/lib/config.js` (`FLASH_HOLD_BROWSE_RATE`, about twice the load that reaches the HPA's 60% CPU target); kept at 60 it/s: it reached 4 replicas and passed the HPA gate on every run |

## Method

`make loadtest` (needs `make cluster-up`; the compose stack must be down):

1. `obs-up`, then `deploy OVERLAY=loadtest`. A sampler container writes `hpa.csv` every 5 s (time, current and desired replicas, CPU percent, ready API pods).
2. `probe-front-door` with the valid token: Envoy's exact 404 is required for ten normalised spellings and all five methods; any 2xx or session cookie on the other variants fails the run (`probe.txt`).
3. Flash sale: wait until the HPA has settled at its minimum, create `flash-<RUN_ID>` with 100 units, snapshot `pg_stat_database`, run `flash-sale`, snapshot again, run `flash-verify.sh`, settle again.
4. `checkout` on `lt-<RUN_ID>-1..5` (1,000,000 units each, so seeded stock is untouched), then `browse`.
5. `hpa-gate.sh` (flash sale only). An exit trap removes the sampler and the database sampler, deactivates this run's test products (earlier runs' products are deactivated by the next run's setup), and runs `deploy OVERLAY=obs`. The cleanup ignores further INT, TERM, HUP and PIPE in the shell and runs the teardown and the closing deploy in their own session with output in `teardown.log` and `restore-obs.log`, so a terminal Ctrl-C, a dead `| tee` pipe or a closed terminal cannot abort it (a SIGKILL or host shutdown still can; recovery is `make deploy OVERLAY=obs`, and `make cluster-e2e` fails while minting is live); if the restore fails, the run says the mint endpoint is still live and writes `RESTORE-FAILED` in the run directory and in `build/reports/loadtest/`. On every exit (success, failure or interrupt) the run fails if any report file holds a session cookie or XSRF token (`__Host-SESSION`, `XSRF-TOKEN` or `"xsrf"` in cookie, JSON, header or URL-encoded form, any case); only a count is printed.

`make loadtest-cache` alternates `loadtest-nocache` and `loadtest-pinned` (HPA fixed at 2 replicas) off, on, off, on, off, on. Each run first does a separate 30 s warm-up k6 run (`CACHE_WARMUP`, its own report directory, not gating), then snapshots `pg_stat_database`, runs the measured `browse` at a fixed arrival rate, snapshots again and takes `kubectl top pods`. The warm-up is outside the snapshots, so the cold-cache misses of cache-on runs are not in the counts. Medians of three runs per mode are reported.

Database transactions per request = (`xact_commit` after minus before) / `http_reqs{load:shop}` of the measured run. Both cover the same window; the only extra database work is `setup()`'s catalog discovery, a handful of requests.

## Scenarios and thresholds

| Scenario | Load | Thresholds (k6 fails the run) |
|---|---|---|
| `browse` | Anonymous, constant arrival 30 it/s for 2 m over `/`, `/api/me`, categories, product lists (pages, category, sort, some text search) and product pages | `http_req_failed{load:shop}` < 1%; `http_req_duration{load:shop}` p95 < 500 ms; checks > 99% |
| `checkout` | Ramp 5 to 20 VUs over 3 m: add to cart, `POST /api/checkout` with a fresh `Idempotency-Key`, pay, complete on the fake provider, poll until `PAID` | failed < 1%; checkout p95 < 1.5 s; `order_paid` > 98%; `time_to_paid` p95 < 10 s; checks > 99% |
| `flash-sale` | 100 units of one product; 150 authenticated VUs buy at once, then, starting `FLASH_HOLD_START` (1 m) after the burst begins, a 3 m hold of anonymous browse at the calibrated rate plus up to 10 authenticated checkout VUs (mostly 409 sold out) | `flash_orders_created` and `flash_orders_paid` <= 100; failed < 1% |
| HPA gate | After the flash sale | Desired replicas above the minimum after t0; a `SuccessfulRescale` event for the `api` HPA at or after t0 whose new size is above the minimum (scale-downs and earlier events do not count); and ready API pods above the minimum between t0 and t1; all three |
| Flash-sale consistency (C17) | After settle | sold <= 100; sold = sum of `CONSUMED` reservations; sold = 100 - `on_hand`. One PASS or FAIL line each in `flash-verify.txt` |

## Results

### Flash sale and autoscaling

| Item | Value |
|---|---|
| Units sold (PAID) / created orders | 100 / 100 (both runs) |
| Sold-out answers (409) | 1,521 and 1,520 |
| `flash-verify.sh` lines | 6 of 6 PASS in both runs (sold 100, consumed 100, on hand 0, reserved 0) |
| API replicas (min, peak) and time from t0 to the first rescale event | 2, 4; desired replicas above 2 after 169 s and 152 s (the scale-up follows the hold, not the few-second burst) |
| `hpa-gate.sh` result | 3 of 3 PASS in both runs |
| API CPU at peak (percent of request) | 219% and 234% (HPA metric, average over the API pods) |
| Valkey CPU and throttling (`container_cpu_cfs_throttled_periods_total`, C13) | not measured; Valkey read 9m CPU after the runs |
| Requests answered 503 during the hold (Valkey or lock timeout; threshold 1%) | 0: `http_req_failed{load:shop}` 0 of about 49,000 requests per run |
| Calibrated `FLASH_HOLD_BROWSE_RATE` | 60 it/s (default kept) |

### Connections (C4)

| Item | Value |
|---|---|
| `SHOW max_connections` | 100 |
| Peak `select count(*) from pg_stat_activity where usename = 'shop'` during the flash sale | 40 (both runs, 10 s samples) |
| Idle connections (expected 2 pods x 5 = 10) | 10 |
| "too many clients" in API or Flyway logs across `make loadtest` and `make loadtest-cache` | none in the API pods' logs at the end of the runs (logs of pods removed by scale-down or redeploy were not kept) |

Arithmetic: four replicas plus one surge pod is 5 x 15 = 75 application connections against 97 non-superuser slots, leaving 22 for CNPG and slack. The dashboard's pending-connections panel is where a shortfall would show.

### Checkout

| Item | Value |
|---|---|
| Achieved iterations per second | about 4.7 (925 and 923 paid orders in about 198 s) |
| Checkout p50 / p95 / p99 | 7.2 / 9.5 / 11.9 ms and 9.1 / 14.2 / 53.8 ms |
| `order_paid` rate | 100% |
| `time_to_paid` p50 / p95 | 1.03 / 1.04 s and 1.03 / 1.11 s |
| Failed requests | 0 |
| Confirmation mails delivered to Mailpit (C25) | not measured |

### Browse, cache off and on

| Mode | Run | Achieved it/s | p50 | p95 | p99 | Error rate | DB transactions per request | API CPU (millicores, 2 pods) |
|---|---|---|---|---|---|---|---|---|
| off | 1 | 30.0 | 2.06 | 3.76 | 4.97 | 0% | 1.44 | 359 |
| off | 2 | 30.0 | 1.88 | 3.35 | 4.53 | 0% | 1.43 | 296 |
| off | 3 | 30.0 | 2.09 | 3.80 | 5.19 | 0% | 1.44 | 273 |
| off | median | 30.0 | 2.06 | 3.76 | 4.97 | 0% | 1.44 | 296 |
| on | 1 | 30.0 | 1.54 | 3.12 | 4.25 | 0% | 0.34 | 244 |
| on | 2 | 30.0 | 1.74 | 3.65 | 5.37 | 0% | 0.34 | 238 |
| on | 3 | 30.0 | 1.65 | 3.28 | 4.64 | 0% | 0.33 | 255 |
| on | median | 30.0 | 1.65 | 3.28 | 4.64 | 0% | 0.34 | 244 |

"Achieved it/s" is iterations over the 120 s measured window (3,601 / 120); k6's own `iterations.rate` includes setup and reads about 27. Latencies in ms over about 15,100 requests per run (`http_req_duration{load:shop}`). API CPU is one `kubectl top pods` reading right after each run (metrics-server's last window), not a run average.

Cache hit ratio (`cache.gets`) during the "on" runs: not read. Reading: the cache cuts database transactions per request by 76% (1.44 to 0.34) and API CPU by about 18%; latency improves by 20% at p50 and 13% at p95. At 30 it/s neither mode is near saturation, so the gain shows as database and CPU headroom rather than latency.

### Catalog query plans (ADR 0003)

`loadtest/sql/explain-catalog.sql` runs `EXPLAIN (ANALYZE, BUFFERS)` for each sort, a category filter, a text search and the count, on the seeded catalog (about 60 products), so plans differ from those of a large catalog.

| Query | Plan node and time | Index needed? |
|---|---|---|
| name | Seq Scan on `product` (60 rows) and Sort, 0.19 ms | no |
| price ascending | Seq Scan and Sort, 0.11 ms | no |
| price descending | Seq Scan and Sort, 0.11 ms | no |
| name in category `kitchen` | Hash Join: Index Scan on `category_slug_key`, Seq Scan on `product`, 0.08 ms | no |
| relevance for `oak board` | Seq Scan with the text filter, 0.06 ms | no |
| count | Seq Scan, Index Only Scan on `category_pkey`, 0.06 ms | no |

### Probe and cleanup

| Check | Result |
|---|---|
| `probe.txt` (front door refuses the token on every variant) | 80 requests, 0 violations in every run; Envoy's 404 on each normalised spelling |
| `make loadtest` twice in a row | r1-1 and r1-2 back to back: every check passed (both ended non-zero from a credential-check bug since fixed; r2-1 then exited 0) |
| `make cluster-e2e` after a load run | 102 passed, 1 skipped; NetworkPolicy 27/27; PDB eviction checks pass |
| Seeded `stock` rows unchanged | not re-checked in these runs; checkout and flash sale use only their own `lt-` and `flash-` products |

## Bottlenecks seen

The first visible limit is checkout during the 150-VU burst: `op:checkout` p95 is about 1.0 s and p99 1.3 to 4.1 s, while all other requests in the same run stay at p95 under 8 ms and the checkout scenario alone runs at p95 under 15 ms. That fits serialisation on the one hot `stock` row; lock waits were not measured, so this is an inference. API CPU (219 to 234% of request) is what drives the HPA to 4 replicas; connections (40 of 100), Valkey and Postgres stayed far from their limits.

## Limits of these numbers

- One host runs the node, the load generator's network path and the observability stack; the 12 CPUs are shared.
- cgroup v1 locally: CPU accounting for the HPA differs from CI's cgroup v2 in detail.
- The seeded catalog is about 60 products; plans and cache behaviour on a large catalog would differ.
- No rate limits (D38) and no Keycloak on the path; real traffic has both.
- Sampling at 0.1 hides most traces during runs.
