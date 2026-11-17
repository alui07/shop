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

# Probed from inside a container, lazily, because the GID differs between Docker Desktop, WSL and CI runners.
DOCKER_SOCK_GID = $(shell docker run --rm -v /var/run/docker.sock:/s $(ALPINE_IMAGE) stat -c %g /s)

AS_HOST := --user $(HOST_UID):$(HOST_GID) -e HOME=/cache/home -v $(CACHE_DIR)/home:/cache/home

MVN_DOCKER := docker run --rm $(AS_HOST) -v $(CURDIR)/services/api:/workspace -v $(CACHE_DIR)/m2:/cache/m2 -w /workspace --entrypoint mvn
MVN_ARGS := -B -ntp -Dmaven.repo.local=/cache/m2/repository

export HOST_UID HOST_GID MAVEN_IMAGE POSTGRES_IMAGE VALKEY_IMAGE MAILPIT_IMAGE NGINX_IMAGE PLAYWRIGHT_IMAGE API_IMAGE WEB_IMAGE WEB_TOOLS_IMAGE MVN_ARGS

# Node tooling for a project directory. Nothing in tests/e2e needs a browser, so it shares the web tools image.
NODE_RUN = docker run --rm $(AS_HOST) -e pnpm_config_store_dir=/cache/pnpm \
	-v $(CURDIR)/$(1):/app -v $(CACHE_DIR)/pnpm:/cache/pnpm -w /app $(WEB_TOOLS_IMAGE)
WEB_RUN = $(call NODE_RUN,apps/web)
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
	api-lint api-test api-build api-stop api-start web-tools web-deps web-lint web-test web-build \
	e2e e2e-deps e2e-lint e2e-version-lock docker-lint workflow-lint compose-lint scan-fs scan-api scan-web

help: ## Show this help
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z0-9_-]+:.*## / {printf "  %-18s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

cache-dirs:
	@mkdir -p $(CACHE_DIR)/m2/repository $(CACHE_DIR)/pnpm $(CACHE_DIR)/trivy $(CACHE_DIR)/home $(CACHE_DIR)/home/.m2/repository build/reports/trivy build/reports/e2e $(IMAGE_DIR)

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

lint: api-lint web-lint e2e-lint e2e-version-lock docker-lint workflow-lint compose-lint ## Spotless, ESLint, tsc, Prettier, hadolint, actionlint, zizmor, compose config

test: api-test web-test ## Unit and integration tests for both apps; fails below 100% coverage

build: api-build web-build ## Build the runtime images shop/api:dev and shop/web:dev

scan: scan-fs scan-api scan-web ## Trivy scans (repo, API image, web image); fails on fixable HIGH/CRITICAL

e2e: build e2e-deps ## Browser tests against the built images; one spec: make e2e E2E_ARGS=specs/home.spec.ts
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

clean: ## Remove the dev stack with its volumes, build outputs and reports (keeps .cache)
	$(COMPOSE) down --volumes --remove-orphans
	$(E2E_COMPOSE) --profile runner down --volumes --remove-orphans
	rm -rf build services/api/target apps/web/dist apps/web/node_modules apps/web/coverage tests/e2e/node_modules tests/e2e/reports

renovate-validate: ## Validate renovate.json with the pinned Renovate image
	docker run --rm -v $(CURDIR)/renovate.json:/work/renovate.json:ro -w /work $(RENOVATE_IMAGE) renovate-config-validator --strict

api-lint: cache-dirs
	$(MVN_DOCKER) $(MAVEN_IMAGE) $(MVN_ARGS) spotless:check

api-test: cache-dirs
	$(MVN_DOCKER) -v /var/run/docker.sock:/var/run/docker.sock --group-add $(or $(DOCKER_SOCK_GID),$(error could not read the Docker socket GID; is Docker reachable?)) \
		--add-host=host.docker.internal:host-gateway -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal -e POSTGRES_IMAGE $(MAVEN_IMAGE) $(MVN_ARGS) verify

api-build:
	docker build --build-arg MAVEN_IMAGE=$(MAVEN_IMAGE) --build-arg JRE_IMAGE=$(JRE_IMAGE) -t $(API_IMAGE) services/api

web-tools:
	docker build --target dev --build-arg NODE_IMAGE=$(NODE_IMAGE) --build-arg NGINX_IMAGE=$(NGINX_IMAGE) \
		--build-arg PNPM_VERSION=$(PNPM_VERSION) -t $(WEB_TOOLS_IMAGE) apps/web

web-deps: cache-dirs web-tools
	$(WEB_RUN) pnpm install --frozen-lockfile

web-lint: web-deps
	$(WEB_RUN) pnpm run lint
	$(WEB_RUN) pnpm run typecheck
	$(WEB_RUN) pnpm run format:check

web-test: web-deps
	$(WEB_RUN) pnpm run test

web-build:
	docker build --build-arg NODE_IMAGE=$(NODE_IMAGE) --build-arg NGINX_IMAGE=$(NGINX_IMAGE) \
		--build-arg PNPM_VERSION=$(PNPM_VERSION) -t $(WEB_IMAGE) apps/web

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
	docker run --rm --user $(HOST_UID):$(HOST_GID) -v $(CURDIR):/work:ro -w /work $(HADOLINT_IMAGE) hadolint services/api/Dockerfile apps/web/Dockerfile

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
