include deploy/versions.mk

.DEFAULT_GOAL := help
SHELL := bash
.SHELLFLAGS := -euo pipefail -c
.NOTPARALLEL:

HOST_UID := $(shell id -u)
HOST_GID := $(shell id -g)

COMPOSE := docker compose -f deploy/compose/compose.yaml
E2E_COMPOSE := docker compose -p shop-e2e -f tests/e2e/compose.yaml
E2E_DOWN := $(E2E_COMPOSE) --profile runner down --volumes --remove-orphans
E2E_ARGS ?=
CACHE_DIR := $(CURDIR)/.cache
REPORT_DIR := build/reports/trivy
IMAGE_DIR := build/images
WEB_TOOLS_IMAGE := shop/web-tools:dev
API_IMAGE := shop/api:dev
WEB_IMAGE := shop/web:dev
K8S_TOOLS_IMAGE := shop/k8s-tools:dev
CLUSTER_NAME := shop
CLUSTER_PORT := 8088
CLUSTER_NODE_URL := http://shop-control-plane:30080
KIND_DIR := $(CACHE_DIR)/kind
CLUSTER_REPORT_DIR := build/reports/cluster-e2e
KIND_LABEL := label=io.x-k8s.kind.cluster=$(CLUSTER_NAME)
# kubeconform's Kubernetes version follows the kind node image: one source of truth.
K8S_VERSION := $(shell echo '$(KIND_NODE_IMAGE)' | sed -E 's/^[^:]*:v([0-9.]+)@.*/\1/')

# Probed from inside a container, lazily, because the GID differs between Docker Desktop, WSL and CI runners.
DOCKER_SOCK_GID = $(shell docker run --rm -v /var/run/docker.sock:/s $(ALPINE_IMAGE) stat -c %g /s)

AS_HOST := --user $(HOST_UID):$(HOST_GID) -e HOME=/cache/home -v $(CACHE_DIR)/home:/cache/home

MVN_DOCKER := docker run --rm $(AS_HOST) -v $(CURDIR)/services/api:/workspace -v $(CACHE_DIR)/m2:/cache/m2 -w /workspace --entrypoint mvn
MVN_ARGS := -B -ntp -Dmaven.repo.local=/cache/m2/repository
# Testcontainers needs the Docker socket; shared by every target that runs the integration tests.
MVN_IT_DOCKER = -v /var/run/docker.sock:/var/run/docker.sock --group-add $(or $(DOCKER_SOCK_GID),$(error could not read the Docker socket GID; is Docker reachable?)) \
	--add-host=host.docker.internal:host-gateway -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e POSTGRES_IMAGE

export HOST_UID HOST_GID MAVEN_IMAGE POSTGRES_IMAGE VALKEY_IMAGE MAILPIT_IMAGE NGINX_IMAGE PLAYWRIGHT_IMAGE API_IMAGE WEB_IMAGE WEB_TOOLS_IMAGE MVN_ARGS \
	K8S_TOOLS_IMAGE KUBECTL_IMAGE KUBECONFORM_IMAGE GOLANG_IMAGE DOCKER_CLI_IMAGE KIND_NODE_IMAGE

KUBECONFORM_ARGS = -strict -summary -kubernetes-version $(K8S_VERSION) -cache /cache \
	-schema-location 'https://raw.githubusercontent.com/yannh/kubernetes-json-schema/$(K8S_SCHEMAS_REF)/{{.NormalizedKubernetesVersion}}-standalone{{.StrictSuffix}}/{{.ResourceKind}}{{.KindSuffix}}.json' \
	-schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/$(CRD_SCHEMAS_REF)/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'

# Only kind gets the Docker socket (cluster create, image load, log export).
KIND = docker run --rm --pull=never $(AS_HOST) -v /var/run/docker.sock:/var/run/docker.sock \
	--group-add $(or $(DOCKER_SOCK_GID),$(error could not read the Docker socket GID; is Docker reachable?)) \
	-v $(KIND_DIR):/cache/kind -v $(CURDIR):/work:ro -v $(CURDIR)/$(CLUSTER_REPORT_DIR):/reports -w /work $(K8S_TOOLS_IMAGE) kind
# kubectl and Helm join the kind network and use the internal kubeconfig; the host network is never used.
K8S_DOCKER = docker run --rm --pull=never -i $(AS_HOST) --network kind -e KUBECONFIG=/cache/kind/kubeconfig \
	-v $(KIND_DIR):/cache/kind -v $(CURDIR):/work:ro -v $(CURDIR)/$(CLUSTER_REPORT_DIR):/reports -w /work
K8S_RUN = $(K8S_DOCKER) $(K8S_TOOLS_IMAGE)
KUBECTL = $(K8S_RUN) kubectl
HELM = $(K8S_RUN) helm

# Installs a Helm release unless the pinned chart version is deployed with the same values file (stamp under .cache/kind).
# $(1) release, $(2) namespace, $(3) chart name and version as Helm lists it, $(4) chart reference and version flags, $(5) values file
define helm_release
stamp=$(KIND_DIR)/helm-$(1).stamp; want="$(3) $$(sha256sum $(5) | cut -d' ' -f1)"; \
if [ "$$(cat $$stamp 2>/dev/null)" = "$$want" ] && $(HELM) list -n $(2) --deployed --filter '^$(1)$$' -o json | grep -qF '"chart":"$(3)"'; then \
	echo "$(1): $(3) already deployed with unchanged values"; \
else \
	$(HELM) upgrade --install $(1) $(4) -f $(5) -n $(2) --create-namespace --wait --timeout 5m && echo "$$want" > $$stamp; \
fi
endef

# Node tooling for a project directory ($(1)), with optional extra docker flags ($(2)). Nothing in tests/e2e needs a browser, so it shares the web tools image.
NODE_RUN = docker run --rm --pull=never $(AS_HOST) -e pnpm_config_store_dir=/cache/pnpm $(2) \
	-v $(CURDIR)/$(1):/app -v $(CACHE_DIR)/pnpm:/cache/pnpm -w /app $(WEB_TOOLS_IMAGE)
# The API's committed OpenAPI document is mounted read-only; the generate scripts read it from OPENAPI_SPEC.
WEB_RUN = $(call NODE_RUN,apps/web,-v $(CURDIR)/services/api/openapi:/spec:ro -e OPENAPI_SPEC=/spec/openapi.json)
E2E_RUN = $(call NODE_RUN,tests/e2e)

# Trivy resolves pom.xml dependencies from $$HOME/.m2/repository; without the mount it hits Maven Central and gets rate limited.
TRIVY := docker run --rm $(AS_HOST) -e TRIVY_CACHE_DIR=/cache/trivy -v $(CACHE_DIR)/trivy:/cache/trivy \
	-v $(CACHE_DIR)/m2/repository:/cache/home/.m2/repository:ro \
	-v $(CURDIR):/src:ro -v $(CURDIR)/build:/build -w /src $(TRIVY_IMAGE)

# Scans once to JSON without failing, converts to SARIF, then gates on the same JSON.
# `trivy convert` has no --ignore-unfixed, so it is applied at scan time and also affects the SARIF.
# $(1) trivy subcommand and target, $(2) report name, $(3) optional cleanup once the scan has read its input
define trivy_report
$(TRIVY) $(1) --quiet --scanners vuln,secret,misconfig --ignore-unfixed --format json --exit-code 0 --output /build/reports/trivy/$(2).json
$(3)
$(TRIVY) convert --quiet --format sarif --output /build/reports/trivy/$(2).sarif /build/reports/trivy/$(2).json
$(TRIVY) convert --scanners vuln,secret,misconfig --format table --severity HIGH,CRITICAL --exit-code 1 /build/reports/trivy/$(2).json
endef

.PHONY: help dev down logs lint test build scan ci fmt clean renovate-validate cache-dirs \
	api-lint api-test api-spec api-build api-stop api-start web-tools web-deps web-lint web-test web-build web-api-client generate \
	e2e e2e-deps e2e-lint e2e-version-lock docker-lint workflow-lint compose-lint scan-fs scan-api scan-web \
	k8s-tools k8s-lint cluster-up cluster-down cluster-smoke cluster-e2e cluster-diagnostics cluster-traffic deploy deploy-apply kubectl

help: ## Show this help
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z0-9_-]+:.*## / {printf "  %-18s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

cache-dirs:
	@mkdir -p $(CACHE_DIR)/m2/repository $(CACHE_DIR)/pnpm $(CACHE_DIR)/trivy $(CACHE_DIR)/home $(CACHE_DIR)/home/.m2/repository build/reports/trivy build/reports/e2e $(IMAGE_DIR) \
		$(KIND_DIR) $(CACHE_DIR)/kubeconform $(CLUSTER_REPORT_DIR)

dev: web-tools web-deps ## Start Postgres, Valkey, Mailpit, API and web dev server (http://localhost:5173)
	$(COMPOSE) up -d
	@echo "web http://localhost:5173  mailpit http://localhost:8025  postgres localhost:5432"
	@echo "The first API start downloads Maven dependencies; follow it with: make logs"

down: ## Stop the dev stack
	$(COMPOSE) down --remove-orphans

logs: ## Follow dev stack logs
	$(COMPOSE) logs -f --tail=100

api-stop: ## Stop the API in the dev stack (to see the unavailable state)
	$(COMPOSE) stop api

api-start: ## Start the API in the dev stack again
	$(COMPOSE) start api

lint: api-lint web-lint e2e-lint e2e-version-lock docker-lint workflow-lint compose-lint k8s-lint ## Spotless, ESLint, tsc, Prettier, hadolint, actionlint, zizmor, compose config, kubeconform

test: api-test web-test ## Unit and integration tests for both apps; fails below 100% coverage

build: api-build web-build ## Build the runtime images shop/api:dev and shop/web:dev

scan: scan-fs scan-api scan-web ## Trivy scans (repo, API image, web image); fails on fixable HIGH/CRITICAL

e2e: build e2e-deps ## Browser tests against the built images; one spec: make e2e E2E_ARGS=specs/catalog.spec.ts
	trap '$(E2E_DOWN)' INT TERM; \
	$(E2E_COMPOSE) up -d --wait --wait-timeout 120 && { $(E2E_COMPOSE) --profile runner run --rm -T e2e ./node_modules/.bin/playwright test $(E2E_ARGS); s=$$?; } || s=$$?; \
	$(E2E_COMPOSE) logs --no-color > build/reports/e2e/stack.log 2>&1 || true; \
	$(E2E_DOWN) || true; \
	exit $$s

ci: ## Everything the PR workflow runs: lint test build scan e2e
	$(MAKE) lint test build scan e2e

fmt: web-deps e2e-deps ## Apply Spotless and Prettier formatting
	$(MVN_DOCKER) $(MAVEN_IMAGE) $(MVN_ARGS) spotless:apply
	$(WEB_RUN) pnpm run format
	$(E2E_RUN) pnpm run format

clean: ## Remove the dev stack, the kind cluster, build outputs and reports (keeps .cache)
	$(COMPOSE) down --volumes --remove-orphans
	$(E2E_COMPOSE) --profile runner down --volumes --remove-orphans
	$(MAKE) cluster-down
	rm -rf build services/api/target apps/web/dist apps/web/node_modules apps/web/coverage tests/e2e/node_modules tests/e2e/reports

renovate-validate: ## Validate renovate.json with the pinned Renovate image
	docker run --rm -v $(CURDIR)/renovate.json:/work/renovate.json:ro -w /work $(RENOVATE_IMAGE) renovate-config-validator --strict

api-lint: cache-dirs
	$(MVN_DOCKER) $(MAVEN_IMAGE) $(MVN_ARGS) spotless:check

api-test: cache-dirs
	$(MVN_DOCKER) $(MVN_IT_DOCKER) $(MAVEN_IMAGE) $(MVN_ARGS) verify

api-spec: cache-dirs ## Rewrite services/api/openapi/openapi.json from the running code (runs only OpenApiContractIT)
	$(MVN_DOCKER) $(MVN_IT_DOCKER) -e UPDATE_OPENAPI=true $(MAVEN_IMAGE) $(MVN_ARGS) \
		-Dit.test=OpenApiContractIT -Dtest=none -Dsurefire.failIfNoSpecifiedTests=false -Djacoco.skip=true verify

# Attestations make the image ID an index digest that changes on every build with the containerd store, which would roll pods on every deploy.
api-build:
	docker build --build-arg MAVEN_IMAGE=$(MAVEN_IMAGE) --build-arg JRE_IMAGE=$(JRE_IMAGE) --provenance=false --sbom=false -t $(API_IMAGE) services/api

web-tools:
	docker build --target dev --build-arg NODE_IMAGE=$(NODE_IMAGE) --build-arg NGINX_IMAGE=$(NGINX_IMAGE) \
		--build-arg PNPM_VERSION=$(PNPM_VERSION) -t $(WEB_TOOLS_IMAGE) apps/web

web-deps: cache-dirs web-tools
	$(WEB_RUN) pnpm install --frozen-lockfile

web-lint: web-deps
	$(WEB_RUN) pnpm run lint
	$(WEB_RUN) pnpm run typecheck
	$(WEB_RUN) pnpm run format:check
	$(WEB_RUN) pnpm run generate:api:check
	$(WEB_RUN) pnpm run generate:routes:check

web-api-client: web-deps ## Regenerate apps/web/src/api/schema.d.ts from the committed OpenAPI document
	$(WEB_RUN) pnpm run generate:api

generate: ## Regenerate the OpenAPI document, the web API client and the route tree, in that order
	$(MAKE) api-spec
	$(MAKE) web-api-client
	$(WEB_RUN) pnpm run generate:routes

web-test: web-deps
	$(WEB_RUN) pnpm run test

web-build:
	docker build --build-arg NODE_IMAGE=$(NODE_IMAGE) --build-arg NGINX_IMAGE=$(NGINX_IMAGE) \
		--build-arg PNPM_VERSION=$(PNPM_VERSION) --provenance=false --sbom=false -t $(WEB_IMAGE) apps/web

e2e-deps: cache-dirs web-tools
	$(E2E_RUN) pnpm install --frozen-lockfile

e2e-lint: e2e-deps
	$(E2E_RUN) pnpm run lint
	$(E2E_RUN) pnpm run typecheck
	$(E2E_RUN) pnpm run format:check

# The Playwright npm package and the browser image must be the same release.
e2e-version-lock:
	@image=$$(sed -n 's/^PLAYWRIGHT_IMAGE := [^:]*:v\([0-9.]*\)-.*/\1/p' deploy/versions.mk); \
	package=$$(sed -n 's/.*"@playwright\/test": *"\([^"]*\)".*/\1/p' tests/e2e/package.json); \
	if [ -z "$$image" ] || [ "$$image" != "$$package" ]; then \
		echo "Playwright version mismatch: image '$$image' (deploy/versions.mk), @playwright/test '$$package' (tests/e2e/package.json)" >&2; exit 1; fi

docker-lint:
	docker run --rm --user $(HOST_UID):$(HOST_GID) -v $(CURDIR):/work:ro -w /work $(HADOLINT_IMAGE) hadolint services/api/Dockerfile apps/web/Dockerfile deploy/k8s-tools/Dockerfile

workflow-lint:
	docker run --rm --user $(HOST_UID):$(HOST_GID) -v $(CURDIR):/repo:ro -w /repo $(ACTIONLINT_IMAGE) -color
	docker run --rm --user $(HOST_UID):$(HOST_GID) -v $(CURDIR):/repo:ro -w /repo $(ZIZMOR_IMAGE) --offline .github/workflows

compose-lint:
	$(COMPOSE) config -q
	$(E2E_COMPOSE) --profile runner config -q

scan-fs: cache-dirs
	$(call trivy_report,fs --skip-dirs .git --skip-dirs .cache --skip-dirs build --skip-dirs '**/node_modules' --skip-dirs '**/target' --skip-dirs '**/dist' --skip-dirs '**/coverage' --skip-dirs tests/e2e/reports .,fs)

scan-api: cache-dirs api-build
	docker save $(API_IMAGE) -o $(IMAGE_DIR)/api.tar
	$(call trivy_report,image --input /build/images/api.tar,api,rm -f $(IMAGE_DIR)/api.tar)

scan-web: cache-dirs web-build
	docker save $(WEB_IMAGE) -o $(IMAGE_DIR)/web.tar
	$(call trivy_report,image --input /build/images/web.tar,web,rm -f $(IMAGE_DIR)/web.tar)

k8s-tools:
	docker build --build-arg KUBECTL_IMAGE=$(KUBECTL_IMAGE) --build-arg DOCKER_CLI_IMAGE=$(DOCKER_CLI_IMAGE) \
		--build-arg GOLANG_IMAGE=$(GOLANG_IMAGE) --build-arg ALPINE_IMAGE=$(ALPINE_IMAGE) \
		--build-arg KIND_VERSION=$(KIND_VERSION) --build-arg HELM_VERSION=$(HELM_VERSION) \
		-t $(K8S_TOOLS_IMAGE) deploy/k8s-tools

cluster-up: cache-dirs k8s-tools ## Create the kind cluster with Calico, Envoy Gateway and CloudNativePG (idempotent)
	@if [ -z "$$(docker ps -aq --filter $(KIND_LABEL))" ]; then \
		$(KIND) create cluster --name $(CLUSTER_NAME) --config deploy/kind/cluster.yaml --image $(KIND_NODE_IMAGE) --kubeconfig /cache/kind/kubeconfig.external; \
	elif [ -z "$$(docker ps -q --filter $(KIND_LABEL))" ]; then \
		docker start $(CLUSTER_NAME)-control-plane; \
	fi
	@( umask 077; $(KIND) get kubeconfig --internal --name $(CLUSTER_NAME) > $(KIND_DIR)/kubeconfig ); chmod 600 $(KIND_DIR)/kubeconfig
	$(KUBECTL) apply --server-side -k deploy/kind/calico
	$(KUBECTL) -n kube-system rollout status ds/calico-node --timeout=300s
	$(KUBECTL) -n kube-system rollout status deploy/calico-kube-controllers --timeout=300s
	$(KUBECTL) wait --for=condition=Ready node --all --timeout=300s
	$(KUBECTL) -n kube-system rollout status deploy/coredns --timeout=300s
	@$(call helm_release,eg,envoy-gateway-system,gateway-helm-$(ENVOY_GATEWAY_CHART_VERSION),oci://docker.io/envoyproxy/gateway-helm --version $(ENVOY_GATEWAY_CHART_VERSION),deploy/kind/envoy-gateway-values.yaml)
	@$(call helm_release,cnpg,cnpg-system,cloudnative-pg-$(CNPG_CHART_VERSION),cloudnative-pg --repo https://cloudnative-pg.github.io/charts --version $(CNPG_CHART_VERSION),deploy/kind/cnpg-values.yaml)
	$(KUBECTL) apply --server-side -f deploy/kind/gatewayclass.yaml
	$(KUBECTL) wait --for=condition=Accepted gatewayclass/envoy-gateway --timeout=120s

cluster-down: ## Remove the kind cluster and its kubeconfig
	@ids=$$(docker ps -aq --filter $(KIND_LABEL)); if [ -n "$$ids" ]; then docker rm -f -v $$ids; fi
	rm -rf $(KIND_DIR)

deploy: build k8s-tools cache-dirs ## Load the :dev images into kind and apply the app (needs make cluster-up)
	@if [ -z "$$(docker ps -q --filter $(KIND_LABEL))" ]; then echo "run make cluster-up first" >&2; exit 1; fi
	@$(MAKE) --no-print-directory deploy-apply cluster-smoke || { $(MAKE) --no-print-directory cluster-diagnostics; exit 1; }
	@echo "http://localhost:$(CLUSTER_PORT)"

# The image ID goes into a pod template annotation so a rebuilt :dev image rolls the pods exactly once and an unchanged one is a no-op.
deploy-apply:
	$(KIND) load docker-image --name $(CLUSTER_NAME) $(API_IMAGE) $(WEB_IMAGE)
	@$(K8S_DOCKER) -e API_IMAGE_ID=$$(docker image inspect -f '{{.Id}}' $(API_IMAGE)) -e WEB_IMAGE_ID=$$(docker image inspect -f '{{.Id}}' $(WEB_IMAGE)) $(K8S_TOOLS_IMAGE) sh -c ' \
		for attempt in 1 2 3; do \
			kubectl kustomize deploy/k8s/overlays/local \
				| sed -e "s/api-image-id-placeholder/$$API_IMAGE_ID/" -e "s/web-image-id-placeholder/$$WEB_IMAGE_ID/" \
				| kubectl apply --server-side --field-manager=shop-deploy -f - && exit 0; \
			echo "apply failed (attempt $$attempt of 3)" >&2; sleep 10; \
		done; exit 1'
	$(KUBECTL) wait --for=condition=Ready cluster.postgresql.cnpg.io/shop-db -n shop --timeout=300s
	$(KUBECTL) -n shop rollout status deploy/api --timeout=300s
	$(KUBECTL) -n shop rollout status deploy/web --timeout=300s
	$(KUBECTL) -n shop wait --for=condition=Programmed gateway/shop --timeout=180s

# Plain bash /dev/tcp from the host (no curl there); HTTP/1.1 because Envoy rejects HTTP/1.0.
cluster-smoke:
	@for path in / /api/status; do \
		ok=; \
		for attempt in $$(seq 30); do \
			if status=$$( exec 3<>/dev/tcp/127.0.0.1/$(CLUSTER_PORT) && printf 'GET %s HTTP/1.1\r\nHost: localhost:%s\r\nConnection: close\r\n\r\n' "$$path" $(CLUSTER_PORT) >&3 && head -n1 <&3 ) 2>/dev/null \
				&& case "$$status" in *' 200 '*) true;; *) false;; esac; then ok=1; break; fi; \
			sleep 2; \
		done; \
		[ -n "$$ok" ] || { echo "smoke check failed: GET $$path on 127.0.0.1:$(CLUSTER_PORT)" >&2; exit 1; }; \
		echo "smoke ok: GET $$path"; \
	done

cluster-e2e: e2e-deps k8s-tools cache-dirs ## Playwright and NetworkPolicy tests against the kind cluster (run make cluster-up deploy first)
	rm -rf $(CLUSTER_REPORT_DIR)
	mkdir -p $(CLUSTER_REPORT_DIR)
	@status=0; \
	$(MAKE) --no-print-directory cluster-smoke || status=1; \
	docker run --rm --init --shm-size 1g --network kind --user $(HOST_UID):$(HOST_GID) -e HOME=/cache/home -e CI=true \
		-e E2E_UPSTREAM=shop-control-plane:30080 -e E2E_REPORT_DIR=/reports \
		-v $(CURDIR)/tests/e2e:/e2e:ro -v $(CACHE_DIR)/home:/cache/home -v $(CURDIR)/$(CLUSTER_REPORT_DIR):/reports -w /e2e \
		$(PLAYWRIGHT_IMAGE) ./node_modules/.bin/playwright test $(E2E_ARGS) || status=1; \
	{ $(K8S_RUN) sh tests/e2e/cluster/network-policy-test.sh 2>&1 | tee $(CLUSTER_REPORT_DIR)/network-policy.log; } || status=1; \
	if [ $$status -ne 0 ]; then $(MAKE) --no-print-directory cluster-diagnostics; fi; \
	exit $$status

cluster-diagnostics: cache-dirs ## Write cluster state and logs to build/reports/cluster-e2e
	@mkdir -p $(CLUSTER_REPORT_DIR)/diagnostics
	@$(K8S_RUN) sh deploy/k8s-tools/diagnostics.sh || true
	@$(KIND) export logs /reports/diagnostics/kind-logs --name $(CLUSTER_NAME) || true

kubectl: cache-dirs ## Run kubectl against the kind cluster, for example: make kubectl ARGS="get pods -n shop"
	@$(KUBECTL) $(ARGS)

cluster-traffic: ## Request /api/status every 100 ms through the gateway and print failures; Ctrl-C to stop
	docker run --rm --init -t --network kind $(ALPINE_IMAGE) sh -c ' \
		n=0; while :; do \
			n=$$((n + 1)); \
			wget -q -T 2 -O /dev/null $(CLUSTER_NODE_URL)/api/status || echo "$$(date -u +%T) FAIL"; \
			if [ $$((n % 50)) -eq 0 ]; then echo "$$n requests"; fi; \
			sleep 0.1; \
		done'

k8s-lint: cache-dirs
	docker run --rm --user $(HOST_UID):$(HOST_GID) -v $(CURDIR)/deploy:/deploy:ro $(KUBECTL_IMAGE) kustomize /deploy/k8s/overlays/local \
		| docker run --rm -i --user $(HOST_UID):$(HOST_GID) -v $(CACHE_DIR)/kubeconform:/cache $(KUBECONFORM_IMAGE) $(KUBECONFORM_ARGS) -
	docker run --rm --user $(HOST_UID):$(HOST_GID) -v $(CACHE_DIR)/kubeconform:/cache -v $(CURDIR):/work:ro -w /work $(KUBECONFORM_IMAGE) \
		$(KUBECONFORM_ARGS) deploy/kind/gatewayclass.yaml tests/e2e/cluster/network-policy-probes.yaml
