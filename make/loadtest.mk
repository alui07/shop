# k6 load tests against the kind cluster (make loadtest, make loadtest-cache) and the guards that keep session minting out of every other stack.
# Included from the Makefile. The scripts under loadtest/scripts run in the k8s-tools image; the scenarios run in the k6 image on the kind network.

K8S_OVERLAYS := $(notdir $(wildcard deploy/k8s/overlays/*))
GUARDED_OVERLAYS := $(filter-out loadtest%,$(K8S_OVERLAYS))
SCENARIO ?=
CACHE_WARMUP ?= 30s
RUN_ID := $(or $(RUN_ID),$(shell date -u +%Y%m%d-%H%M%S))
LOADTEST_REPORT_ROOT := build/reports/loadtest
LOADTEST_MINT_URL := http://shop-control-plane:30090
LOADTEST_RESTORE_MARKER := $(LOADTEST_REPORT_ROOT)/RESTORE-FAILED

# Compose files must never mention loadtest, a backslash-u escape or spring.application.json (any spelling). $(1) is the compose command; the rendered config is inspected in a variable and never printed.
define forbid_loadtest_in_compose
out=$$($(1) config) || exit 1; case "$$out" in *[Ll][Oo][Aa][Dd][Tt][Ee][Ss][Tt]*) echo "loadtest appears in the rendered compose config ($(1))" >&2; exit 1;; esac; \
if printf '%s\n' "$$out" | grep -qF '\u' || printf '%s\n' "$$out" | grep -qiE 'spring[._-]application[._-]json'; then echo "a backslash-u escape or spring.application.json appears in the rendered compose config ($(1))" >&2; exit 1; fi
endef

# Shell prelude for the load test recipes: helpers for the scripts and k6. $(1) run directory on the host.
# The token is read into this shell only and passed to k6 by name, so it never appears in a command line or in output.
define loadtest_prelude
command -v setsid >/dev/null || { echo "setsid (util-linux) is required: the cleanup runs the restore in its own session" >&2; exit 1; }; \
run_dir=$(1); mkdir -p "$$run_dir"; \
token=$$(grep -m1 '^SHOP_LOADTEST_TOKEN=' $(SECRETS_FILE) | cut -d= -f2-); [ -n "$$token" ] || { echo "SHOP_LOADTEST_TOKEN missing; run make secrets" >&2; exit 1; }; \
tools() { local dir=$$1; shift; docker run --rm --init --pull=never -i $(AS_HOST) --network kind -e KUBECONFIG=/cache/kind/kubeconfig -e RUN_ID=$(RUN_ID) -v $(KIND_DIR):/cache/kind \
	-v $(CURDIR):/work:ro -v "$(CURDIR)/$$dir:/reports" -w /work $(K8S_TOOLS_IMAGE) "$$@"; }; \
background_tools() { local name=$$1 dir=$$2; shift 2; docker run -d --init --name $$name --pull=never $(AS_HOST) --network kind -e KUBECONFIG=/cache/kind/kubeconfig -e RUN_ID=$(RUN_ID) -v $(KIND_DIR):/cache/kind \
	-v $(CURDIR):/work:ro -v "$(CURDIR)/$$dir:/reports" -w /work $(K8S_TOOLS_IMAGE) "$$@" > /dev/null; }; \
k6() { local dir=$$1 script=$$2; shift 2; SHOP_LOADTEST_TOKEN=$$token docker run --rm --network kind --user $(HOST_UID):$(HOST_GID) -e HOME=/tmp \
	-v $(CURDIR)/loadtest:/loadtest:ro -v "$(CURDIR)/$$dir:/reports" -e BASE_URL=$(CLUSTER_NODE_URL) -e MINT_URL=$(LOADTEST_MINT_URL) -e RUN_ID=$(RUN_ID) $(if $(FLASH_HOLD_RATE),-e FLASH_HOLD_RATE=$(FLASH_HOLD_RATE)) -e SHOP_LOADTEST_TOKEN \
	"$$@" $(K6_IMAGE) run /loadtest/scenarios/$$script.js; }
endef

# Cleanup messages must never abort the cleanup: stdout or stderr may be a dead pipe or a hung-up terminal. $(1) is the message (no commas).
define loadtest_say
{ echo "$(1)" >&2 2>/dev/null || true; }
endef

# Run from both cleanups, so failed and interrupted runs are checked too. Sets s=1 when any file under $(1) holds a session cookie or XSRF token (case-insensitive). Only a count is printed, never a match.
define loadtest_forbid_credentials
leaked=$$({ grep -rhiE '__Host-SESSION[=":%]|XSRF-TOKEN[=":%]|"xsrf"[[:space:]]*:' $(1) 2>/dev/null || true; } | wc -l); \
if [ "$$leaked" -gt 0 ]; then s=1; $(call loadtest_say,$$leaked lines in $(1) hold session cookies or XSRF tokens; delete the reports); fi
endef

# Final step of both cleanups: put the obs overlay back. It runs in its own session without a terminal and with its output in a file, so Ctrl-C, a closed terminal and a dead output pipe cannot reach the Docker CLIs inside it
# (docker re-enables SIGINT and forwards it to its container even when the shell ignores it). A failure leaves minting live, so it is printed and also written to marker files, because the terminal may be gone.
# make cluster-e2e fails while the fixed marker exists. A successful restore, or any later successful make deploy of a non-loadtest overlay (the manual recovery), removes it. The run-dir marker stays as a record.
define loadtest_restore_obs
if setsid -w $(MAKE) --no-print-directory deploy OVERLAY=obs < /dev/null > "$$run_dir/restore-obs.log" 2>&1; then rm -f $(LOADTEST_RESTORE_MARKER); \
else s=1; for marker in "$$run_dir/RESTORE-FAILED" $(LOADTEST_RESTORE_MARKER); do { echo "restoring overlays/obs failed: the loadtest session-minting endpoint (NodePort 30090) is STILL LIVE. Recover with: make deploy OVERLAY=obs (when it succeeds it removes the marker in build/reports/loadtest/). Log: $$run_dir/restore-obs.log" > "$$marker"; } 2>/dev/null || true; done; \
$(call loadtest_say,ERROR: restoring overlays/obs failed. The loadtest session-minting endpoint (NodePort 30090) is STILL LIVE. Run: make deploy OVERLAY=obs. Log: $$run_dir/restore-obs.log); fi
endef

# Last step of make deploy: a successful deploy of a non-loadtest overlay means minting is gone, so the fixed marker of a failed restore is stale. Uses OVERLAY from the deploy invocation.
define loadtest_clear_restore_marker
case "$(OVERLAY)" in loadtest*) ;; *) if [ -f $(LOADTEST_RESTORE_MARKER) ]; then rm -f $(LOADTEST_RESTORE_MARKER); echo "removed $(LOADTEST_RESTORE_MARKER): overlay $(or $(OVERLAY),default) deployed, session minting is gone"; fi;; esac
endef

# Refuses to run beside the compose dev stack (memory budget, C19).
define loadtest_refuse_compose
if [ -n "$$(docker ps -q --filter label=com.docker.compose.project=shop)" ]; then echo "the compose dev stack (project shop) is running; run make down first" >&2; exit 1; fi
endef

# Used by cluster-e2e: fails while the minting route or NodePort 30090 exists, or while a failed restore left its marker. Catches a run killed with SIGKILL, a host shutdown mid-run or a failed restore.
define loadtest_refuse_live_minting
failed=; \
if [ -f $(LOADTEST_RESTORE_MARKER) ]; then echo "$(LOADTEST_RESTORE_MARKER) exists: a load test restore failed. $$(cat $(LOADTEST_RESTORE_MARKER)) Run: make deploy OVERLAY=obs, which removes the marker when it succeeds" >&2; failed=1; fi; \
route=$$($(KUBECTL) -n shop get httproute loadtest -o name --ignore-not-found); \
nodeports=$$($(KUBECTL) get services -A -o jsonpath='{range .items[*]}{range .spec.ports[*]}{.nodePort}{"\n"}{end}{end}'); \
if [ -n "$$route" ] || printf '%s\n' "$$nodeports" | grep -qx 30090; then echo "session minting is live (HTTPRoute loadtest in namespace shop or a Service on NodePort 30090). Run: make deploy OVERLAY=obs" >&2; failed=1; fi; \
[ -z "$$failed" ] || exit 1
endef

.PHONY: loadtest loadtest-cache loadtest-lint k8s-loadtest-guard

# Every overlay not named loadtest* must render no loadtest configuration (make/forbid-loadtest-env.awk) and must keep the front door's /api/loadtest not-found rule.
k8s-loadtest-guard:
	@for overlay in $(GUARDED_OVERLAYS); do \
		out=$$(docker run --rm --user $(HOST_UID):$(HOST_GID) -v $(CURDIR)/deploy:/deploy:ro $(KUBECTL_IMAGE) kustomize /deploy/k8s/overlays/$$overlay) || exit 1; \
		printf '%s\n' "$$out" | awk -f make/forbid-loadtest-env.awk || { echo "overlays/$$overlay renders loadtest configuration" >&2; exit 1; }; \
		printf '%s\n' "$$out" | awk -f make/require-notfound-rule.awk || { echo "overlays/$$overlay lost the /api/loadtest not-found rule" >&2; exit 1; }; \
	done

loadtest-lint: ## k6 inspect on every scenario in loadtest/scenarios
	@set -- loadtest/scenarios/*.js; [ -e "$$1" ] || { echo "no scenarios in loadtest/scenarios" >&2; exit 1; }; \
	for file in "$$@"; do \
		docker run --rm --user $(HOST_UID):$(HOST_GID) -e HOME=/tmp -v $(CURDIR)/loadtest:/loadtest:ro $(K6_IMAGE) inspect /loadtest/scenarios/$$(basename $$file) > /dev/null || exit 1; \
	done

loadtest: secrets k8s-tools cache-dirs ## Flash sale, checkout and browse under k6 with the HPA gate (needs cluster-up); SCENARIO=browse|checkout|flash-sale runs one; reports in build/reports/loadtest/<RUN_ID>
	@case "$(SCENARIO)" in ''|browse|checkout|flash-sale) ;; *) echo "SCENARIO must be browse, checkout or flash-sale" >&2; exit 1;; esac
	@$(loadtest_refuse_compose)
	@$(call loadtest_prelude,$(LOADTEST_REPORT_ROOT)/$(RUN_ID)); \
	want() { [ -z "$(SCENARIO)" ] || [ "$(SCENARIO)" = "$$1" ]; }; \
	rc=0; \
	teardown() { docker rm -f shop-loadtest-sampler shop-loadtest-dbstats >/dev/null 2>&1 || true; \
		tools $$run_dir sh loadtest/scripts/sql.sh checkout-teardown.sql run=$(RUN_ID) >/dev/null 2>&1 || true; \
		tools $$run_dir sh loadtest/scripts/sql.sh flash-teardown.sql run=$(RUN_ID) >/dev/null 2>&1 || true; \
		tools $$run_dir sh loadtest/scripts/settle.sh /reports/hpa.csv --warn-only || true; }; \
	cleanup() { s=$$?; trap - EXIT; trap '' INT TERM HUP PIPE; export run_dir; export -f tools teardown; \
		setsid -w bash -c teardown < /dev/null > "$$run_dir/teardown.log" 2>&1 || true; \
		$(call loadtest_forbid_credentials,$$run_dir); \
		$(loadtest_restore_obs); exit $$s; }; \
	trap cleanup EXIT; trap 'exit 130' INT TERM HUP; \
	$(MAKE) --no-print-directory obs-up; \
	$(MAKE) --no-print-directory deploy OVERLAY=loadtest; \
	docker rm -f shop-loadtest-sampler shop-loadtest-dbstats >/dev/null 2>&1 || true; \
	background_tools shop-loadtest-sampler $$run_dir sh loadtest/scripts/hpa-sampler.sh /reports/hpa.csv; \
	k6 $$run_dir probe-front-door; \
	if want flash-sale; then \
		tools $$run_dir sh loadtest/scripts/settle.sh /reports/hpa.csv; \
		tools $$run_dir sh loadtest/scripts/sql.sh flash-setup.sql run=$(RUN_ID); \
		tools $$run_dir sh loadtest/scripts/db-stats.sh /reports/db-flash-before.txt; \
		background_tools shop-loadtest-dbstats $$run_dir sh -c 'while :; do sh loadtest/scripts/db-stats.sh /reports/db-flash-sample-$$(date +%s).txt >/dev/null 2>&1; sleep 10; done'; \
		date +%s > $$run_dir/t0; \
		k6 $$run_dir flash-sale || rc=1; \
		date +%s > $$run_dir/t1; \
		docker rm -f shop-loadtest-dbstats >/dev/null 2>&1 || true; \
		tools $$run_dir sh loadtest/scripts/db-stats.sh /reports/db-flash-after.txt || true; \
		tools $$run_dir sh loadtest/scripts/flash-verify.sh /reports $(RUN_ID) || rc=1; \
		[ -n "$(SCENARIO)" ] || tools $$run_dir sh loadtest/scripts/settle.sh /reports/hpa.csv || rc=1; \
	fi; \
	if want checkout; then \
		tools $$run_dir sh loadtest/scripts/sql.sh checkout-setup.sql run=$(RUN_ID); \
		k6 $$run_dir checkout || rc=1; \
	fi; \
	if want browse; then k6 $$run_dir browse $(if $(RATE),-e RATE=$(RATE)) $(if $(DURATION),-e DURATION=$(DURATION)) || rc=1; fi; \
	if want flash-sale; then tools $$run_dir sh loadtest/scripts/hpa-gate.sh /reports || rc=1; fi; \
	echo "reports: $$run_dir"; exit $$rc

# Alternates cache off and on three times on the pinned two-replica overlays; each run does a separate warm-up k6 run first, then snapshots pg_stat_database around the measured run only.
loadtest-cache: secrets k8s-tools cache-dirs ## Browse with the catalog cache off and on, three runs each on two pinned replicas (needs cluster-up)
	@$(loadtest_refuse_compose)
	@$(call loadtest_prelude,$(LOADTEST_REPORT_ROOT)/$(RUN_ID)); \
	root=$$run_dir; rc=0; \
	cleanup() { s=$$?; trap - EXIT; trap '' INT TERM HUP PIPE; \
		$(call loadtest_forbid_credentials,$$root); \
		$(loadtest_restore_obs); exit $$s; }; \
	trap cleanup EXIT; trap 'exit 130' INT TERM HUP; \
	$(MAKE) --no-print-directory obs-up; \
	explained=; \
	for round in 1 2 3; do for mode in off on; do \
		if [ $$mode = off ]; then overlay=loadtest-nocache; else overlay=loadtest-pinned; fi; \
		dir=$$root/cache-$$mode-$$round; mkdir -p $$dir $$dir/warmup; \
		$(MAKE) --no-print-directory deploy OVERLAY=$$overlay; \
		if [ -z "$$explained" ]; then explained=1; k6 $$dir probe-front-door; tools $$dir sh loadtest/scripts/sql.sh explain-catalog.sql run=$(RUN_ID) > $$root/explain-catalog.txt; fi; \
		k6 $$dir/warmup browse -e DURATION=$(CACHE_WARMUP) $(if $(RATE),-e RATE=$(RATE)) || echo "warm-up run did not meet browse thresholds (not gating)" >&2; \
		tools $$dir sh loadtest/scripts/db-stats.sh /reports/db-before.txt; \
		k6 $$dir browse $(if $(RATE),-e RATE=$(RATE)) $(if $(DURATION),-e DURATION=$(DURATION)) || rc=1; \
		tools $$dir sh loadtest/scripts/db-stats.sh /reports/db-after.txt; \
		tools $$dir kubectl top pods -n shop > $$dir/top-pods.txt 2>&1 || true; \
	done; done; \
	echo "reports: $$root"; exit $$rc
