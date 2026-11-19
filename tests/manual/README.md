# Manual checks

Checks that cannot be automated here. Each has the reason, the steps, the expected result and a box to tick. Copy the list into an issue or tick it in place on a branch.

## One-time local setup

- [ ] **Docker group login**
  - Why manual: it depends on your login session, which no script in the repo can change.
  - Steps: add your user to the group (`sudo usermod -aG docker $USER`), log out and in (on WSL, run `wsl --shutdown` from Windows), then run `docker ps` and `make help`.
  - Expected: `docker ps` works without `sudo` or `sg docker`.

- [ ] **First `make dev` smoke test with hot reload**
  - Why manual: it needs a browser and a person watching a file change.
  - Steps: run `make dev`, then `make logs` until the API reports it started (the first start downloads Maven dependencies and is slow). Open http://localhost:5173. The catalog should list products. Edit a visible text in a file under `apps/web/src` and save, then revert the edit (unit and e2e tests assert the text).
  - Expected: the catalog shows seeded products, `/status` shows status `ok` and a version, and the edited text appears without a manual reload. Mailpit opens at http://localhost:8025. `make down` stops everything.

- [ ] **IDE setup (optional)**
  - Why manual: the host has no JDK or Node by design, so an IDE cannot resolve dependencies on its own.
  - Steps: give the IDE its own JDK 25 and Node 26 with pnpm 12, or open the repo in a dev container. Point the IDE at `services/api/pom.xml` and `apps/web/package.json`. Run `make web-deps` and `make e2e-deps` first so `apps/web/node_modules` and `tests/e2e/node_modules` exist.
  - Expected: the IDE shows no unresolved imports. Use `make` for building and testing; the IDE is for editing.

- [ ] **`make e2e` on WSL with Docker Desktop**
  - Why manual: CI runs the suite on Linux only, and Docker Desktop networking differs (the Testcontainers host override exists for the same reason).
  - Steps: run `make e2e` locally, once with a cold cache and once again.
  - Expected: both runs pass and `docker ps` shows no leftover `shop-e2e` containers afterwards.

## Kubernetes (kind)

- [ ] **Cluster smoke, rollouts, policies and cleanup (after changes under `deploy/`)**
  - Why manual: it needs a person watching a browser, rollouts and timing.
  - Steps:
    1. `make cluster-up deploy`. Browse http://localhost:8088: catalog, a product, `/status`. The browser console shows no CSP violations.
    2. Run `make deploy` again with nothing changed. Pod ages stay the same (`make kubectl ARGS="-n shop get pods"`). Change a visible string in the web app and run `make deploy`: only `web` rolls. Revert the change.
    3. Zero-drop rollout: in terminal A run `make cluster-traffic`. In terminal B run `make kubectl ARGS="-n shop rollout restart deploy/api"`, then `make kubectl ARGS="-n shop rollout status deploy/api"`. Terminal A must print no `FAIL`. Repeat for `web`.
    4. Database outage: `make kubectl ARGS="-n shop delete pod shop-db-1"`. `/status` shows the API unavailable, then recovers without a reload once CNPG restarts the instance.
    5. Pod Security: `make kubectl ARGS="-n shop run pss-check --image=busybox --restart=Never -- id"` is rejected by `restricted`.
    6. `make kubectl ARGS="-n shop get networkpolicy"` lists the sixteen policies. `make kubectl ARGS="get pods -A"` shows Calico, Envoy Gateway and CNPG running.
    7. Memory: `docker stats shop-control-plane --no-stream`; compare with the figure in `README.md` (about 3.5 GiB with Keycloak).
    8. `make cluster-down`. `docker ps` shows no `shop-control-plane`.
  - Expected: every step behaves as described.

- [ ] **`cluster-e2e` job passes on the first pull request**
  - Why manual: the job has never run on a GitHub runner.
  - Steps: open the pull request and watch the `cluster-e2e` job next to `ci`.
  - Expected: both pass. Record the job's duration in `docs/plans/STATUS.md`.

## Authentication (slice 3a)

Run these on `make dev` (Keycloak on http://localhost:8180) unless a step says kind. Passwords for `customer@example.test` and `admin@example.test` are in `.cache/secrets/shop.env`; read them from the file, do not paste them into notes or issues.

- [ ] **Back-channel logout from the account console**
  - Why manual: it needs a person in the Keycloak account console; e2e covers the IdP-initiated logout endpoint only.
  - Steps: sign in as the customer at http://localhost:5173. In another tab open http://localhost:8180/realms/shop/account, go to Device activity (Signed in devices), and choose "Sign out all devices". Reload the shop tab.
  - Expected: the shop shows you signed out (`/api/me` answers `authenticated: false`) without any action in that tab.

- [ ] **Keycloak restart, then sign out (C3)**
  - Why manual: it needs a restart mid-session and a person watching the browser.
  - Steps: on kind (`make cluster-up deploy`, http://localhost:8088), sign in. Run `make kubectl ARGS="-n shop rollout restart deploy/keycloak"` and wait for `rollout status` (2 to 3 minutes). Click Sign out in the shop.
  - Expected: the app session ends before the redirect; Keycloak may show an error page at the end of logout, and the SPA tolerates it. Reloading the shop shows you signed out. The restart re-created the realm, so signing in again needs the form.

- [ ] **Valkey restart signs everyone out**
  - Why manual: it needs a person to restart a dependency and watch the browser.
  - Steps: sign in on `make dev`. Run `docker compose -f deploy/compose/compose.yaml --env-file .cache/secrets/shop.env restart valkey` (or on kind `make kubectl ARGS="-n shop rollout restart deploy/valkey"`). Reload.
  - Expected: you are signed out; the catalog works throughout. While Valkey is down, a reload with the stale cookie may show an error and the anonymous catalog without a cookie stays up.

- [ ] **Keycloak down**
  - Why manual: it needs a person to stop a dependency and watch several states over time.
  - Steps: sign in on `make dev`. Run `docker compose -f deploy/compose/compose.yaml --env-file .cache/secrets/shop.env stop keycloak`. Browse the catalog, then open a private window and press Sign in. In the signed-in window keep using the site (admin page for the admin). Start Keycloak again afterwards.
  - Expected: the catalog works; sign-in cannot reach Keycloak (the browser shows a connection error; if the API cannot validate the token the home page shows the failed-login alert); the existing session keeps working until the revalidation grace (15 minutes after the last successful check) ends, then the next request signs you out.

- [ ] **Absolute session timeout**
  - Why manual: it takes 8 hours of wall clock; the automated test moves a fake clock.
  - Steps: sign in and keep the tab active at least every 20 minutes (idle timeout is 30). After 8 hours, make any request, for example open `/admin` as the admin.
  - Expected: the session has ended: signed out, no admin data. `SessionTimeoutIT` proves the rule with a fake clock.

**Expired `id_token_hint` on kind (C23): not testable by hand**
  - Why there is no manual check: the revalidation filter runs before the logout filter, so after more than 5 minutes `POST /logout` first refreshes the ID token and the hint is fresh again. An expired hint reaches Keycloak only while Keycloak is down and the 15-minute grace applies, which cannot end in a working logout redirect anyway.
  - Proof instead: the compose e2e spec `logout.spec.ts` (10 second token lifespan) shows Keycloak 26.8 accepts an expired hint; it skips on kind. No fallback is built.

- [ ] **Memory reading with Keycloak**
  - Why manual: the numbers depend on the machine.
  - Steps: with `make cluster-up deploy` finished and the app signed in once, run `docker stats shop-control-plane --no-stream`. Separately, with `make dev` up, run `docker stats --no-stream`.
  - Expected: the kind node is near 3.5 GiB (README); Keycloak is 600 to 750 MB per instance. Do not run `make dev`, `make e2e` and the cluster together; they approach the 12 GB WSL limit. If the numbers drift, update `README.md`.

- [ ] **Sign-in and admin page by keyboard and screen reader**
  - Why manual: axe does not judge focus order or announcements.
  - Steps: with `make dev`, use only the keyboard to open Sign in, complete the Keycloak form, reach the user menu, open Admin, search and page the table, then sign out. Repeat with a screen reader.
  - Expected: logical, visible focus; the sign-in prompt, "no access" panel and failed-login alert are announced.

## Admin writes (slice 3b)

Run these on `make dev` (http://localhost:5173) as `admin@example.test` unless a step says otherwise. Read passwords from `.cache/secrets/shop.env`; do not paste them into notes or issues.

- [ ] **Create, edit, stock change and audit in the dev stack**
  - Why manual: it checks the dev stack end to end with a person reading the screens; e2e covers the same flow on the built images.
  - Steps: open Admin, then "New product". Create a product with `Active` unchecked and note that the public catalog does not list it. Open its edit page, tick `Active`, save, and look for it in the public catalog. Set its stock and save. Open the Audit log.
  - Expected: each save shows a success message and the next save works without reloading; the audit log shows `product.created`, `product.updated` and `stock.set` for the product, newest first, with your display name and the changed fields. Deactivate the product afterwards.

- [ ] **Two tabs edit the same product (conflict)**
  - Why manual: e2e simulates the second tab with a stale request; this checks what a person sees.
  - Steps: open the same product's edit page in two tabs. Save a change in the first, then a different change in the second.
  - Expected: the second tab shows a conflict alert, nothing is overwritten, and reloading the form shows the first tab's values. The same holds for the stock form.

- [ ] **A busy product answers "try again" (lock timeout)**
  - Why manual: e2e does not hold a database lock for 3 seconds.
  - Steps: in `psql` (command in the audit check below), run `begin; select * from product where slug = 'oak-desk' for update;` and leave it open. In the shop, edit Oak Desk and save. Then `rollback;` and save again.
  - Expected: after about 3 seconds the form shows "The product is busy; try again shortly" and nothing changes; after the rollback the save works.

- [ ] **A demoted admin cannot write during a Keycloak outage (E5)**
  - Why manual: it needs a role change in the Keycloak console, a stopped dependency and about 5 minutes of waiting; the automated test moves a fake clock.
  - Steps: sign in as the admin and open a product's edit page. In the Keycloak admin console (http://localhost:8180, realm `shop`) remove the admin role from `admin@example.test`. Stop Keycloak: `docker compose -f deploy/compose/compose.yaml --env-file .cache/secrets/shop.env stop keycloak`. Wait more than 5 minutes after your last successful request to the API, then save the product. Start Keycloak again afterwards.
  - Expected: the form shows the message "Your access cannot be confirmed right now" (HTTP 503 with `Retry-After: 300`) and nothing changes; the audit log has no row for it. Reads of the admin table still work until the 15-minute grace ends. With Keycloak up, the demotion takes effect within about 5 minutes: a save then gets 403 or the session ends.

- [ ] **Audit log after a demotion**
  - Why manual: it needs a person to judge what the log shows; the log records the stable `sub`, and the table shows the display name.
  - Steps: make a change as the admin, demote the user in Keycloak as above, then sign in as another admin if you have one, or read the rows with `docker compose -f deploy/compose/compose.yaml --env-file .cache/secrets/shop.env exec postgres psql -U shop -c "select actor_subject, actor_name, action from admin_audit_event order by id desc limit 5"`.
  - Expected: earlier rows are still present and unchanged with the actor's `sub`; `UPDATE` or `DELETE` on the table is refused by the trigger.

- [ ] **Admin forms by keyboard and screen reader**
  - Why manual: axe does not judge focus order, announcements or error wording.
  - Steps: using only the keyboard, create a product, submit it empty and with a bad slug, fix the fields, save, then set stock. Repeat with a screen reader.
  - Expected: logical, visible focus; each field error is announced with its field and moved to when submitting; saved and conflict messages are announced; the audit table has readable headers.

- [ ] **Migrations V3 to V5 on an existing kind database**
  - Why manual: it needs a cluster that ran 3a first, which a fresh `make cluster-up` does not give.
  - Steps: on a cluster deployed from the 3a branch (`make cluster-up deploy`), switch to this branch and run `make deploy`. Check `make kubectl ARGS="-n shop logs deploy/api"` for Flyway.
  - Expected: Flyway applies V3, V4 and V5 without waiting on a lock (each begins with a 5 s lock timeout), every product has a stock row, and the catalog still works.

## After the first push to GitHub

- [ ] **Pull request workflow is green on a Linux runner**
  - Why manual: the workflows and the Testcontainers host override (`TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal`) have never run on a GitHub `ubuntu-24.04` runner. The override was needed on Docker Desktop and is unverified elsewhere.
  - Steps: push the branch, open a pull request, watch the `CI` workflow.
  - Expected: the `ci` job passes. If `make test` fails on Testcontainers or Ryuk connectivity, adjust `api-test` in the `Makefile` and record the change in `docs/adr/0001-containerised-tooling.md`.

- [ ] **Failing CI run uploads the e2e artifact**
  - Why manual: it only happens on a failing run, which you have to cause.
  - Steps: on a throwaway branch, break an assertion in `tests/e2e/specs/catalog.spec.ts` and push.
  - Expected: the `ci` job fails and an `e2e-reports` artifact is attached to the run, containing `html/` and `stack.log`. Delete the branch afterwards.

- [ ] **Trivy results appear under code scanning**
  - Why manual: code scanning needs a public repository or GitHub Advanced Security, so uploads are skipped while the repo is private (and for fork pull requests).
  - Steps: once the repo is public, push to `main` or open a same-repo pull request. Open Security, Code scanning.
  - Expected: alerts or an empty result under the categories `trivy-fs`, `trivy-api` and `trivy-web`. Decide now whether to make the repo public at this point or at slice 6.

- [ ] **First weekly CodeQL run is not skipped**
  - Why manual: the schedule event's payload may lack `repository.visibility`, which would make the guard skip the job.
  - Steps: after the repo is public, wait for the first scheduled run (Actions, CodeQL), or note that none has run yet.
  - Expected: the scheduled run analyses. If it is skipped, add `github.event_name == 'schedule' ||` to the visibility guard in `.github/workflows/codeql.yaml`.

- [ ] **Renovate app installed and dashboard appears**
  - Why manual: installing a GitHub app is an account setting.
  - Steps: install the Renovate GitHub app on the repo, then open Issues.
  - Expected: a "Dependency Dashboard" issue appears and Renovate opens a configuration or update pull request. Run `make renovate-validate` first if the config changed.

- [ ] **Renovate moves Playwright as one unit**
  - Why manual: it needs a real Renovate run on GitHub, after a new Playwright release is older than 7 days. The image tags on the Microsoft registry have no release date, so `renovate.json` exempts the image from the age gate; this has only been checked in a local dry run.
  - Steps: wait for the first `playwright` pull request.
  - Expected: it changes `@playwright/test` in `tests/e2e/package.json`, its lockfile and `PLAYWRIGHT_IMAGE` in `deploy/versions.mk` together, and `make lint` passes. If the image moves in a pull request on its own, the version lock in `make lint` fails it; wait for the npm package to pass the age gate and let Renovate update the branch.

- [ ] **Renovate is not holding base images back**
  - Why manual: a local dry run showed `maven` and `nginxinc/nginx-unprivileged` updates marked pending because their registry tags carry no release date, so the 7-day age gate may hold them indefinitely. Only a real Renovate run shows whether that happens.
  - Steps: open the Dependency Dashboard issue and look under "Pending Status Checks" a week or more after an image release.
  - Expected: no image stays pending for longer than 7 days. If one does, decide between a scoped `minimumReleaseAgeBehaviour: "timestamp-optional"` rule in `renovate.json` (as already done for the Playwright image) and bumping that image by hand.

- [ ] **Branch protection requires the `ci` check**
  - Why manual: it is a repository setting.
  - Steps: Settings, Branches, add a rule for `main`: require a pull request and the status check `ci`.
  - Expected: a pull request with a failing `ci` job cannot be merged. The check name only appears in the list after the workflow has run once.

## Recurring

- [ ] **Content Security Policy in browser dev tools, on the built image**
  - Why manual: e2e checks for violations, but only a person sees what a real browser reports. The Vite dev server (`make dev`) does not enforce the CSP, so it cannot be used for this.
  - Steps: run `make build`. Start the built web image on a loopback port: `docker run --rm -p 127.0.0.1:8081:8080 shop/web:dev` (the web image listens on 8080). Open http://127.0.0.1:8081, then open dev tools: Network, click the document request, and read the response headers (`Content-Security-Policy`, `X-Content-Type-Options`, `Referrer-Policy`, `X-Frame-Options`, `Cross-Origin-Opener-Policy`, `Permissions-Policy`; no `Strict-Transport-Security`). Open the Console and reload, and click through `/status` and a deep link such as `/products/x`. Optionally request a missing file, `/assets/missing.js`, and expect a 404 and not the app page.
  - Expected: all headers present, no CSP violation messages in the Console. Stop the container with Ctrl+C.
  - What this does and does not show: it proves the built image sends the policy and that the page loads under it. This container has no API behind it (the web image does not proxy `/api`), so catalog data requests fail and the app shows its error state; that is expected here and exercises only the error screen. The data flows under the CSP are covered by `make e2e`, whose stack publishes no ports. To see catalog pages under the CSP in your own browser use the kind cluster (see Kubernetes (kind) below).

- [ ] **Catalog with keyboard only (each time the UI changes)**
  - Why manual: axe cannot judge whether the tab order and focus feel right.
  - Steps: with `make dev` running (wait for the API; check `make logs`), open http://localhost:5173 and use only the keyboard. Tab to "Search products", type a word, press Enter. Tab to "Category" and "Sort by" and change them with the arrow keys. Tab through the product cards, open one with Enter, and return. Tab to the pagination links and use Next and Previous.
  - Expected: every control is reachable in a sensible order, focus is always visible, results and the URL update, and nothing needs a mouse.

- [ ] **Search with partial words shows the documented limit**
  - Why manual: it confirms the known limit in `docs/adr/0003-catalog-data-and-api.md` still matches behaviour and the UI copy.
  - Steps: with `make dev` running, search for a whole word that appears in a product name, then for the first few letters of it only (for example `lam` instead of `lamp`), then for a misspelling.
  - Expected: the whole word finds products; the partial word and the misspelling find nothing and show "No products match". If partial words start matching, update the ADR and the README's known limits.

- [ ] **Keyboard and screen-reader pass (each time the UI changes)**
  - Why manual: axe-core covers only part of WCAG, and the `role="alert"` announcement of "API unavailable" cannot be verified without assistive technology.
  - Steps: with `make dev` running, use only the keyboard to reach everything interactive on the catalog, a product page and http://localhost:5173/status. Turn on a screen reader (NVDA, VoiceOver or Orca), open http://localhost:5173/status and stop the API with `make api-stop`. Start it again with `make api-start`.
  - Expected: focus order is logical and visible, and on `/status` the screen reader announces "API unavailable" without moving focus.

- [ ] **One non-Chromium browser smoke (before a release)**
  - Why manual: the e2e suite runs Chromium only.
  - Steps: with `make dev` running, open http://localhost:5173 in Firefox and in Safari or WebKit if available. Search, filter, sort, change page and open a product. Open http://localhost:5173/status. Run `make api-stop`, watch `/status`, then reload the catalog. Run `make api-start`, watch `/status` again, and press "Try again" on the catalog.
  - Expected: the catalog and product page behave as in Chromium. With the API down, `/status` shows "API unavailable" and recovers on its own once the API is back; the catalog shows "Something went wrong" and recovers when "Try again" is pressed.

- [ ] **Release checks**
  - None yet. Add items here when a release process exists.
