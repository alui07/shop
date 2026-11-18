ARG KUBECTL_IMAGE
ARG DOCKER_CLI_IMAGE
ARG GOLANG_IMAGE
ARG ALPINE_IMAGE

FROM ${KUBECTL_IMAGE} AS kubectl
FROM ${DOCKER_CLI_IMAGE} AS docker-cli

FROM ${GOLANG_IMAGE} AS build
ARG KIND_VERSION
ARG HELM_VERSION
ENV CGO_ENABLED=0 GOTOOLCHAIN=local GOFLAGS=-trimpath GOBIN=/out
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    go install "sigs.k8s.io/kind@${KIND_VERSION}" && \
    go install "helm.sh/helm/v4/cmd/helm@${HELM_VERSION}"

FROM ${ALPINE_IMAGE}
COPY --from=kubectl /bin/kubectl /usr/local/bin/kubectl
COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=build /out/ /usr/local/bin/
USER 65532
