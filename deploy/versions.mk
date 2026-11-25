# Single source of tool and image versions. Renovate updates these through the
# annotations: each annotation applies to the assignment on the next line.

# renovate: datasource=docker depName=maven
MAVEN_IMAGE := maven:3.9.16-eclipse-temurin-25@sha256:93b8a14ea2f412782e4e842651273b4d903e35cc496284f178fbbe2d67d00976
# renovate: datasource=docker depName=eclipse-temurin
JRE_IMAGE := eclipse-temurin:25.0.5_8-jre-alpine-3.24@sha256:e1898fd6a11fc965a994d8ace186fd3585aeb636cf8f05f47a723fcfee0b0918
# renovate: datasource=docker depName=node
NODE_IMAGE := node:26.13.1-alpine3.24@sha256:6bd9ab43c0ec0190ca7b2d193906cb8b7edf6e7f4afa43ddeccd6452710bffb5
# renovate: datasource=docker depName=nginxinc/nginx-unprivileged
NGINX_IMAGE := nginxinc/nginx-unprivileged:1.30.6-alpine-slim@sha256:5d8bb9437d74a600ec5dbf90c035451c724dc33eac8d3a71078f6f8612c345f6
# renovate: datasource=npm depName=pnpm
PNPM_VERSION := 12.21.0

# renovate: datasource=docker depName=postgres
POSTGRES_IMAGE := postgres:18.7-alpine3.24@sha256:b6972e1115a4ff8eba5d7f7d19184926d1e16d7d1136ae1e1a7ebf286da0f015
# renovate: datasource=docker depName=valkey/valkey
VALKEY_IMAGE := valkey/valkey:9.1.3-alpine3.24@sha256:bf09d58e96d6b4524a15396b65b242bbe18beebbcc15a04d8ee75ddac62fae0b
# renovate: datasource=docker depName=axllent/mailpit
MAILPIT_IMAGE := axllent/mailpit:v1.33.1@sha256:aef2aacc11f075ec0fb6874275ee4a421e688e11a96ebb296134d1735c7761ce
# Official image only; our Dockerfile re-optimises it. quay.io gives no release timestamps, so Renovate treats the age gate as optional for it.
# 26.8.2 bumped by hand on 2026-11-19: the 26.8.2-3 rebuild (2026-11-19) carries OpenJDK 21.0.13.1.2, which fixes CVE-2026-114938 (CRITICAL).
# renovate: datasource=docker depName=quay.io/keycloak/keycloak
KEYCLOAK_IMAGE := quay.io/keycloak/keycloak:26.8.2@sha256:7e66a96e4739239070aeaf72e4c5decf0f7f682cfc725b9f7ea2b336eaba38b3

# renovate: datasource=docker depName=aquasec/trivy
TRIVY_IMAGE := aquasec/trivy:0.76.2@sha256:8f6bb947dbc528902e11f2f5be52f7b519f6a39d5d148f41166f2040499768fb
# renovate: datasource=docker depName=hadolint/hadolint
HADOLINT_IMAGE := hadolint/hadolint:v2.15.1-alpine@sha256:a1d49ae1a4e83c1dbad26b8c1ad7588c8bd1e04f4866b34ad3cac50335198552
# renovate: datasource=docker depName=rhysd/actionlint
ACTIONLINT_IMAGE := rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667
# renovate: datasource=docker depName=ghcr.io/zizmorcore/zizmor
ZIZMOR_IMAGE := ghcr.io/zizmorcore/zizmor:1.32.0@sha256:3014cc8f68f663935f69b3c3e0e41f5ce102b268d97db13db0f62208747f860d
# renovate: datasource=docker depName=mcr.microsoft.com/playwright
PLAYWRIGHT_IMAGE := mcr.microsoft.com/playwright:v1.65.0-noble@sha256:9beb546099e11f52d62e505537914870e01a67e0e6b00ae41da4775324e73dbe
# renovate: datasource=docker depName=ghcr.io/renovatebot/renovate
RENOVATE_IMAGE := ghcr.io/renovatebot/renovate:44.247.2@sha256:5b777fc44b9736e4bd656ba7c54dc54fb76290b2701983c0bb06f16df0c54ad5
# renovate: datasource=docker depName=alpine
ALPINE_IMAGE := alpine:3.24@sha256:f59abe656b692d901e184be3445e084786602108599d2a51d229c0f5a48c71bf

# renovate: datasource=go depName=sigs.k8s.io/kind
KIND_VERSION := v0.33.0
# The node image digest comes from the kind release notes; bump it by hand together with KIND_VERSION.
# renovate: datasource=docker depName=kindest/node
KIND_NODE_IMAGE := kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed
# renovate: datasource=docker depName=registry.k8s.io/kubectl
KUBECTL_IMAGE := registry.k8s.io/kubectl:v1.36.6@sha256:0650805141538d13ccbb6bb9e0dd647bc415bc002898357970211c4d47224396
# renovate: datasource=go depName=helm.sh/helm/v4
HELM_VERSION := v4.3.1
# renovate: datasource=docker depName=ghcr.io/yannh/kubeconform
KUBECONFORM_IMAGE := ghcr.io/yannh/kubeconform:v0.8.0-alpine@sha256:6b90a5f23d846140ce0194fe050b1995e546eba938f3a6bf10c039dd5e24588f
# renovate: datasource=docker depName=golang
GOLANG_IMAGE := golang:1.27.3-alpine3.24@sha256:fa3d596898d9f126af7c01d14ee7597bab3f39cccf392ef36b69fe77cd681f18
# renovate: datasource=docker depName=docker
DOCKER_CLI_IMAGE := docker:29.9.2-cli@sha256:5e6baf67325a79f64ed3bed6863954d5e05d0f3d4e57cbdb14809346814bbfdb

# renovate: datasource=docker depName=docker.io/envoyproxy/gateway-helm
ENVOY_GATEWAY_CHART_VERSION := v1.10.0
# renovate: datasource=helm depName=cloudnative-pg registryUrl=https://cloudnative-pg.github.io/charts
CNPG_CHART_VERSION := 0.29.1

# renovate: datasource=helm depName=metrics-server registryUrl=https://kubernetes-sigs.github.io/metrics-server/
METRICS_SERVER_CHART_VERSION := 3.14.0
# Image of chart 3.14.0 (app v0.9.0); the chart pins only by tag, so the digest is passed with --set. Bump together with the chart.
# renovate: datasource=docker depName=registry.k8s.io/metrics-server/metrics-server
METRICS_SERVER_IMAGE := registry.k8s.io/metrics-server/metrics-server:v0.9.0@sha256:d9862115e7c7881280d3d75ca26bda8ffc0fc213315979575bf23ce9826205c0

# Observability stack for `make obs-up` (kind) and `make dev OBS=1` (compose). Grafana and Tempo charts moved to grafana-community (2026-01-30); Loki stays on grafana.github.io.
# Chart-bundled images (Prometheus, Grafana, Tempo, Loki) stay pinned by the chart's own tag; digests come with slice 6.
# renovate: datasource=helm depName=prometheus registryUrl=https://prometheus-community.github.io/helm-charts
PROMETHEUS_CHART_VERSION := 29.47.2
# renovate: datasource=helm depName=grafana registryUrl=https://grafana-community.github.io/helm-charts
GRAFANA_CHART_VERSION := 14.4.1
# renovate: datasource=helm depName=tempo registryUrl=https://grafana-community.github.io/helm-charts
TEMPO_CHART_VERSION := 3.2.1
# renovate: datasource=helm depName=loki registryUrl=https://grafana.github.io/helm-charts
LOKI_CHART_VERSION := 7.6.0
# renovate: datasource=helm depName=opentelemetry-collector registryUrl=https://open-telemetry.github.io/opentelemetry-helm-charts
OTEL_COLLECTOR_CHART_VERSION := 0.178.3
# The k8s distribution (chart command.name otelcol-k8s); the chart sets no default repository. Same release as the chart's appVersion.
# renovate: datasource=docker depName=otel/opentelemetry-collector-k8s
OTEL_COLLECTOR_IMAGE := otel/opentelemetry-collector-k8s:0.163.2@sha256:cce4142919331aa26dd9e2900b0583dac12c44d2f87c270d3a2110912d4785a9
# renovate: datasource=docker depName=grafana/otel-lgtm
OTEL_LGTM_IMAGE := grafana/otel-lgtm:0.41.0@sha256:2e3290657f2f2a256c7a9175f097424a3ac5b54840741ace433e108e109348b6

# Load generator for `make loadtest`; the tag was pushed 2026-11-12.
# renovate: datasource=docker depName=grafana/k6
K6_IMAGE := grafana/k6:2.4.0@sha256:d41bb79cd8de5a7ad619b53570e3712633e8bff0937010a5c6c57a4747e7a276

# Schema commits for k8s-lint; update by hand together with the node image and chart versions (the CRD schemas must match the charts).
K8S_SCHEMAS_REF := 9367d1ce1c88b8bbf6ee06d0e80510f26394913f
CRD_SCHEMAS_REF := 49729e49e8f062933e3716a26540d41b0cd0856c
