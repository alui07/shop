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
    6. `make kubectl ARGS="-n shop get networkpolicy"` lists the seven policies. `make kubectl ARGS="get pods -A"` shows Calico, Envoy Gateway and CNPG running.
    7. Memory: `docker stats shop-control-plane --no-stream`; compare with the figure in `README.md`.
    8. `make cluster-down`. `docker ps` shows no `shop-control-plane`.
  - Expected: every step behaves as described.

- [ ] **`cluster-e2e` job passes on the first pull request**
  - Why manual: the job has never run on a GitHub runner.
  - Steps: open the pull request and watch the `cluster-e2e` job next to `ci`.
  - Expected: both pass. Record the job's duration in `docs/plans/STATUS.md`.

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
