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
