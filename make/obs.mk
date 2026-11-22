# Observability: the kind `obs` namespace (make obs-up) and the compose `obs` profile (make dev OBS=1). Included from the Makefile.

OBS_NAMESPACE := obs
OBS_URL := http://grafana.localhost:$(CLUSTER_PORT)
# Sum of memory requests in the obs namespace must stay under this (C10).
OBS_MEMORY_REQUEST_LIMIT_MIB := 1024

# `make dev OBS=1`: start the otel-lgtm profile and switch the API's OTLP export on. Other targets never export.
# The export settings live in compose.obs.yaml, which only OBS=1 adds; the base compose file never reads OTEL_* or MANAGEMENT_* from the shell.
OBS_COMPOSE_ENV = $(if $(OBS),COMPOSE_PROFILES=obs)
COMPOSE_OBS_FILE = $(if $(OBS),-f deploy/compose/compose.obs.yaml)

# The collector image is passed as repository plus digest: the chart sets no default repository and renders `repository@digest` when a digest is given.
OTEL_COLLECTOR_REPOSITORY = $(firstword $(subst :, ,$(OTEL_COLLECTOR_IMAGE)))
OTEL_COLLECTOR_DIGEST = sha256:$(lastword $(subst :, ,$(OTEL_COLLECTOR_IMAGE)))

# NetworkPolicies only overlays/obs (and overlays built on it) add; make deploy deletes each one the rendered overlay lacks, because server-side apply never removes objects.
OBS_ONLY_NETWORK_POLICIES := api-to-otel-collector

# Rolls the Grafana pod when its admin password changes; the chart reads the Secret only at start. Expanded after `secrets` has run.
GRAFANA_ADMIN_PASSWORD_HASH = $(shell grep '^GRAFANA_ADMIN_PASSWORD=' $(SECRETS_FILE) | sha256sum | cut -c1-32)

.PHONY: obs-up obs-down obs-check obs-check-dev obs-memory

obs-up: secrets k8s-tools cache-dirs ## Install Prometheus, Loki, Tempo, an OpenTelemetry Collector and Grafana in kind (needs make cluster-up); http://grafana.localhost:8088
	@if [ -z "$$(docker ps -q --filter $(KIND_LABEL))" ]; then echo "run make cluster-up first" >&2; exit 1; fi
	$(KUBECTL) create namespace $(OBS_NAMESPACE) --dry-run=client -o yaml | $(K8S_DOCKER) $(K8S_TOOLS_IMAGE) kubectl apply --server-side --field-manager=shop-deploy -f - > /dev/null
	@$(K8S_DOCKER) -v $(SECRETS_DIR):/secrets:ro $(K8S_TOOLS_IMAGE) sh -c ' \
		set -e; . /secrets/shop.env; \
		kubectl -n $(OBS_NAMESPACE) create secret generic grafana-admin --from-literal="admin-user=admin" --from-literal="admin-password=$$GRAFANA_ADMIN_PASSWORD" \
			--dry-run=client -o yaml | kubectl apply --server-side --field-manager=shop-deploy -f - > /dev/null'
	$(KUBECTL) -n $(OBS_NAMESPACE) create configmap shop-dashboards --from-file=deploy/observability/dashboards --dry-run=client -o yaml \
		| $(K8S_DOCKER) $(K8S_TOOLS_IMAGE) kubectl apply --server-side --field-manager=shop-deploy -f - > /dev/null
	@$(call helm_release,loki,$(OBS_NAMESPACE),loki-$(LOKI_CHART_VERSION),loki --repo https://grafana.github.io/helm-charts --version $(LOKI_CHART_VERSION),deploy/observability/loki-values.yaml)
	@$(call helm_release,tempo,$(OBS_NAMESPACE),tempo-$(TEMPO_CHART_VERSION),tempo --repo https://grafana-community.github.io/helm-charts --version $(TEMPO_CHART_VERSION),deploy/observability/tempo-values.yaml)
	@$(call helm_release,prometheus,$(OBS_NAMESPACE),prometheus-$(PROMETHEUS_CHART_VERSION),prometheus --repo https://prometheus-community.github.io/helm-charts --version $(PROMETHEUS_CHART_VERSION),deploy/observability/prometheus-values.yaml)
	@$(call helm_release,otel-collector,$(OBS_NAMESPACE),opentelemetry-collector-$(OTEL_COLLECTOR_CHART_VERSION),opentelemetry-collector --repo https://open-telemetry.github.io/opentelemetry-helm-charts --version $(OTEL_COLLECTOR_CHART_VERSION) --set image.repository=$(OTEL_COLLECTOR_REPOSITORY) --set image.digest=$(OTEL_COLLECTOR_DIGEST),deploy/observability/collector-values.yaml,$(OTEL_COLLECTOR_IMAGE))
	@$(call helm_release,grafana,$(OBS_NAMESPACE),grafana-$(GRAFANA_CHART_VERSION),grafana --repo https://grafana-community.github.io/helm-charts --version $(GRAFANA_CHART_VERSION) --set-string podAnnotations.checksum/admin-password=$(GRAFANA_ADMIN_PASSWORD_HASH),deploy/observability/grafana-values.yaml,$(GRAFANA_ADMIN_PASSWORD_HASH))
	$(KUBECTL) apply --server-side --field-manager=shop-deploy -f deploy/observability/grafana-route.yaml
	@$(MAKE) --no-print-directory obs-memory
	@echo "Grafana $(OBS_URL) (anonymous Viewer; admin password is GRAFANA_ADMIN_PASSWORD in $(SECRETS_FILE)). Export telemetry with: make deploy OVERLAY=obs"

# Fails when the containers in the obs namespace request more than OBS_MEMORY_REQUEST_LIMIT_MIB, or when one requests no memory at all.
obs-memory:
	@$(KUBECTL) -n $(OBS_NAMESPACE) get pods -o go-template='{{range .items}}{{range .spec.containers}}{{with .resources}}{{with .requests}}{{.memory}}{{end}}{{end}}{{"\n"}}{{end}}{{end}}' \
		| awk -v limit=$(OBS_MEMORY_REQUEST_LIMIT_MIB) ' \
			{ v = $$0; unit = v; sub(/^[0-9.]+/, "", unit); sub(/[A-Za-z]+$$/, "", v); \
			  mib = (unit == "Gi") ? v * 1024 : (unit == "Mi") ? v : (unit == "Ki") ? v / 1024 : (unit == "G") ? v * 953.67 : (unit == "M") ? v * 0.95367 : (unit == "") ? v / 1048576 : -1; \
			  if ($$0 == "" || mib < 0) { bad++; next } total += mib } \
			END { printf "obs memory requests: %.0f MiB (limit %d MiB)\n", total, limit; \
			      if (bad) { print "a container in obs has no memory request or an unknown unit" > "/dev/stderr"; exit 1 } \
			      if (total > limit) { print "obs memory requests exceed the limit" > "/dev/stderr"; exit 1 } }'

obs-down: ## Remove the observability stack from kind
	@for release in grafana otel-collector prometheus tempo loki; do \
		$(HELM) uninstall $$release -n $(OBS_NAMESPACE) --ignore-not-found --wait || exit 1; \
		rm -f $(KIND_DIR)/helm-$$release.stamp; \
	done
	$(KUBECTL) delete namespace $(OBS_NAMESPACE) --ignore-not-found --wait=true

obs-check: ## Every metric the dashboard uses is in the kind Prometheus (deploy with OVERLAY=obs first)
	$(K8S_RUN) sh deploy/observability/check-metrics.sh kind

obs-check-dev: ## Every metric the dashboard uses is in otel-lgtm's Prometheus (after make dev OBS=1)
	docker run --rm --network shop_default -v $(CURDIR)/deploy/observability:/obs:ro $(ALPINE_IMAGE) sh /obs/check-metrics.sh http://otel-lgtm:9090
