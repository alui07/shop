# Slice 2 implementation brief: deploy slice 1 to kind

Branch: `slice-2-kind-deploy`. Read first: `master-plan.md` (slice 2 line, Kubernetes row), `docs/adr/0001-containerised-tooling.md`, `tests/README.md`, `slice-1-brief.md` amendments. All standing rules apply: every tool runs in a container through the Makefile as the host UID, pinned versions in `deploy/versions.mk` (tag plus digest, Renovate annotation), 7-day release-age gate, 100% coverage gates (no new exclusions), no git index or history changes (read-only `git status`, `git diff`, `git log` only), no secrets in the repo or terminal, comments only for non-obvious workarounds, files under 500 lines, `sg docker -c "..."` when the shell lacks the docker group. Nothing is installed on the host.

## Goal

Slice 1 runs in a local kind cluster behind Envoy Gateway, with Postgres from CloudNativePG, restricted Pod Security, default-deny NetworkPolicies and the same Playwright suite. Done means:

- `make cluster-up deploy` on a fresh checkout ends with `http://localhost:8088` serving the seeded catalog; running it again changes nothing and restarts no pods.
- `make cluster-e2e` passes: front-door smoke check on `127.0.0.1:8088`, all existing Playwright specs plus one new routing spec against the cluster, and the NetworkPolicy test (two allowed and seven denied connections).
- `make lint` includes `k8s-lint` and passes; `make ci` is otherwise unchanged and still compose-based.
- The new `cluster-e2e` CI job passes on the PR, in parallel with `ci`.
- `make cluster-down` removes everything; `make clean` also removes the cluster if one exists.

## Versions (checked 2026-11-17 against GitHub releases, registries and vendor docs; all at least 7 days old)

| Thing | Pin | Source and notes |
|---|---|---|
| kind | `v0.33.0` (2026-08-26) | GitHub release. Built from source with `go install` (below) |
| kind node image | `kindest/node:v1.36.4@sha256:099e049362a1526b2db71494e1947aae99bd16290d7c895f2b7ea312e3cbfaed` | kind v0.33.0 release notes (pre-built list). Not the 1.37.0 default: CNPG 1.30 supports Kubernetes 1.34 to 1.36 (1.37 is "tested, not supported") and Calico 3.32 is tested on 1.34 to 1.36 |
| kubectl | `registry.k8s.io/kubectl:v1.36.6@sha256:0650805141538d13ccbb6bb9e0dd647bc415bc002898357970211c4d47224396` (2026-10-28) | Official image (distroless, binary at `/bin/kubectl`). Also used for `kubectl kustomize` |
| Kustomize | `kubectl kustomize` (embedded in kubectl 1.36) | No separate binary |
| Helm | `v4.3.1` (2026-10-15) | 4.3.2 (2026-11-13) is inside the gate. No official image: built from source |
| kubeconform | `ghcr.io/yannh/kubeconform:v0.8.0-alpine@sha256:6b90a5f23d846140ce0194fe050b1995e546eba938f3a6bf10c039dd5e24588f` (2026-06-04) | Official image, entrypoint `/kubeconform` |
| Core schemas | `yannh/kubernetes-json-schema` commit `9367d1ce1c88b8bbf6ee06d0e80510f26394913f` (2026-11-09), dir `v1.36.4-standalone-strict` | Verified the path exists at that commit |
| CRD schemas | `datreeio/CRDs-catalog` commit `49729e49e8f062933e3716a26540d41b0cd0856c` (2026-11-08) | Contains Envoy Gateway 1.10.0, CNPG 1.30.1 and Gateway API 1.6.1 schemas, matching the pins here |
| Envoy Gateway chart | `oci://docker.io/envoyproxy/gateway-helm` `v1.10.0` (2026-11-06) | Compatibility matrix: Envoy distroless-v1.40.x, Gateway API v1.6.1 (CRDs bundled via the chart's `crds` subchart, so `helm upgrade` updates them), Kubernetes 1.34 to 1.37 |
| CloudNativePG chart | `cloudnative-pg` `0.29.1` from `https://cloudnative-pg.github.io/charts` (2026-09-23), operator 1.30.1 | 0.29.2 / 1.30.2 published today, inside the gate |
| Postgres operand | `ghcr.io/cloudnative-pg/postgresql:18.6-202611090812-minimal-trixie@sha256:ec2ed3404e89fff99643901b58a2a34c46a46345fe69a3d96500b82f995c327b` | CNPG 18.7 images were first built 2026-11-12, inside the gate. Same major as compose (18.7); Renovate moves it to 18.7 next week |
| Calico | manifest `v3.32.3` (2026-10-28) | Docs: 3.32 tested on Kubernetes 1.34 to 1.36. 3.33 moves to separate v3 CRD charts that need MutatingAdmissionPolicy; not worth it here |
| Go (tools build) | `golang:1.27.3-alpine3.24@sha256:fa3d596898d9f126af7c01d14ee7597bab3f39cccf392ef36b69fe77cd681f18` | Helm and kubeconform need Go 1.26 or later |
| Docker CLI (for kind) | `docker:29.9.2-cli@sha256:5e6baf67325a79f64ed3bed6863954d5e05d0f3d4e57cbdb14809346814bbfdb` | Static binary at `/usr/local/bin/docker` |

Helm 4, Gateway API v1.6, Envoy Gateway 1.10, CNPG 1.30 and Kubernetes 1.36 are newer than most training data: check the official docs before trusting memory.

## NetworkPolicy enforcement: Calico, not kindnet

kindnet in kind v0.33.0 always enables NetworkPolicy through `sigs.k8s.io/kube-network-policies` (`images/kindnetd/cmd/kindnetd/main.go`; feature since kind v0.24.0). It queues packets with the nftables `queue` expression, and its config is `FailOpen: true`. The WSL2 kernel on the owner's machine (`5.15.167.4-microsoft-standard-WSL2`, which Docker Desktop shares) has `# CONFIG_NFT_QUEUE is not set` and no loadable module, so on WSL kindnet would skip or fail open and policies would silently not apply. kindnet also always accepts UDP 53. Decision: `disableDefaultCNI: true` and Calico v3.32.3 (iptables data plane) on every machine, so laptop and CI enforce the same way. The WSL kernel has what Calico's iptables mode needs (`IP_SET` hash types, `XT_SET`, conntrack, `XT_MARK`, `NFT_COMPAT`, REJECT). IPIP is off (single node needs no encapsulation, and `ipip` is only a module on WSL). The automated test below is what proves enforcement on each machine.

## Tooling: `shop/k8s-tools:dev` (`deploy/k8s-tools/Dockerfile`)

kind and Helm have no official images, so a multi-stage image built like `shop/web-tools:dev` (build args from `versions.mk`, `FROM ${ARG}` without defaults):

1. `FROM ${KUBECTL_IMAGE} AS kubectl`, `FROM ${DOCKER_CLI_IMAGE} AS docker-cli`.
2. `FROM ${GOLANG_IMAGE} AS build` with `CGO_ENABLED=0 GOTOOLCHAIN=local GOFLAGS=-trimpath GOBIN=/out`, then `go install sigs.k8s.io/kind@${KIND_VERSION}` and `go install helm.sh/helm/v4/cmd/helm@${HELM_VERSION}` with BuildKit cache mounts for the module and build caches. The Go module proxy and the checksum database (sum.golang.org) verify every module, so integrity is checked without hand-kept SHA-256 values and Renovate can bump the version alone. Neither module has `replace` directives (checked).
3. `FROM ${ALPINE_IMAGE}`: copy `/bin/kubectl`, `/usr/local/bin/docker` and `/out/*` to `/usr/local/bin`. No `apk add`; scripts are POSIX `sh` (busybox). `USER 65532` (the Makefile overrides it with the host UID).

kubeconform is not in this image; lint uses the official image, so `make lint` (and the `ci` job) never compiles Go.

### How containers reach Docker and the cluster

- **kind** runs from the tools image with `/var/run/docker.sock` mounted and `--group-add $(DOCKER_SOCK_GID)` (the existing lazy probe). Only kind gets the socket: `cluster-up`, `cluster-down`, `deploy` (image load) and `cluster-diagnostics` (log export). ADR 0001's socket trade-off is extended to these targets.
- **kubectl and Helm** never use the host network (its meaning differs on Docker Desktop). They join the Docker network `kind` and use the internal kubeconfig from `kind get kubeconfig --internal --name shop` (server `https://shop-control-plane:6443`), written by the host shell to `.cache/kind/kubeconfig` with mode 600. kind's own external kubeconfig goes to `.cache/kind/kubeconfig.external` and is not used. `.cache` is gitignored and skipped by `scan-fs`. Never print either file.
- All tool containers run with `AS_HOST` (host UID, `HOME=/cache/home`), so Helm's cache lands in `.cache/home/.cache/helm`. The repo is mounted read-only at `/work`, `.cache/kind` read-write at `/cache/kind`.
- Identical on GitHub `ubuntu-24.04` runners: the socket GID is probed the same way and the `kind` network exists once the cluster does.

## kind cluster (`deploy/kind/`)

`deploy/kind/cluster.yaml` (`kind.x-k8s.io/v1alpha4`):
- `name: shop`, one `control-plane` node (node container `shop-control-plane`). One node keeps memory low and the NodePort mapping trivial; multi-node buys nothing for slice 2.
- `networking`: `apiServerAddress: 127.0.0.1`, `disableDefaultCNI: true`, `podSubnet: 10.244.0.0/16`, `serviceSubnet: 10.96.0.0/16`.
- `extraPortMappings`: `containerPort: 30080`, `hostPort: 8088`, `listenAddress: 127.0.0.1`, `protocol: TCP`. Port 8088 clashes with nothing in compose (5173, 5432, 8025) or the e2e stack (no published ports).
- No `image:` in the file; the Makefile passes `--image $(KIND_NODE_IMAGE)` so `versions.mk` stays the single source.

Also in `deploy/kind/`:
- `calico/kustomization.yaml`: resource `https://raw.githubusercontent.com/projectcalico/calico/v3.32.3/manifests/calico.yaml`, plus a strategic-merge patch on DaemonSet `calico-node` (namespace `kube-system`, container `calico-node`) setting env `CALICO_IPV4POOL_CIDR=10.244.0.0/16` and `CALICO_IPV4POOL_IPIP=Never` (`CALICO_IPV4POOL_VXLAN` is already `Never`). Applied with `kubectl apply --server-side -k` because the CRDs exceed the client-side annotation limit.
- `envoy-gateway-values.yaml`: chart defaults except `deployment.envoyGateway.resources` requests `cpu: 50m, memory: 128Mi`, limit `memory: 512Mi`.
- `cnpg-values.yaml`: `resources` requests `cpu: 50m, memory: 128Mi`, limit `memory: 512Mi`; everything else default (cluster-wide, CRDs and webhook from the chart).
- `gatewayclass.yaml`: GatewayClass `envoy-gateway` (`gateway.networking.k8s.io/v1`), `controllerName: gateway.envoyproxy.io/gatewayclass-controller`. It is cluster-scoped, so it belongs to cluster setup rather than the app overlay.

## Manifests (`deploy/k8s/`)

Common labels on every app object: `app.kubernetes.io/name: <api|web>`, `app.kubernetes.io/part-of: shop`. **Deployments and Services select on both labels; NetworkPolicies select on `app.kubernetes.io/name` only.** The NetworkPolicy test relies on this: its probe pods carry `name` but not `part-of`, so Services never route to them. Do not use `commonLabels` (it rewrites selectors); write labels out.

`deploy/k8s/base/`:
- `kustomization.yaml`: `namespace: shop`; resources below.
- `namespace.yaml`: Namespace `shop` with `pod-security.kubernetes.io/{enforce,audit,warn}: restricted` and `...-version: v1.36`.
- `api.yaml`: Deployment and Service `api`.
  - Deployment: `replicas: 1`, `revisionHistoryLimit: 3`, `strategy.rollingUpdate: {maxSurge: 1, maxUnavailable: 0}`. Pod: `automountServiceAccountToken: false`, `enableServiceLinks: false`, `terminationGracePeriodSeconds: 40`, pod `securityContext: {runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, seccompProfile: {type: RuntimeDefault}}`.
  - Container `api`, image `shop-api` (renamed by the overlay). Ports `http` 8080, `management` 8081. Container `securityContext: {allowPrivilegeEscalation: false, readOnlyRootFilesystem: true, capabilities: {drop: [ALL]}}`. `/tmp` from `emptyDir: {sizeLimit: 128Mi}` (Tomcat work dirs, JVM perf data).
  - Env: `SPRING_DATASOURCE_URL=jdbc:postgresql://shop-db-rw:5432/shop`; `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` from secret `shop-db-app`, keys `username` and `password` (do not use the secret's `jdbc-uri`, it carries the password in the URL); `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError`.
  - Resources: requests `cpu: 250m, memory: 512Mi`; limits `cpu: "2", memory: 768Mi` (the CPU limit is generous so JVM startup is not throttled).
  - Probes on port `management`: `startupProbe` `/actuator/health/liveness` (`periodSeconds: 2, failureThreshold: 90, timeoutSeconds: 2`, so up to 3 minutes while Flyway waits for the database); `livenessProbe` `/actuator/health/liveness` (`periodSeconds: 10, timeoutSeconds: 2, failureThreshold: 3`); `readinessProbe` `/actuator/health/readiness` (`periodSeconds: 5, timeoutSeconds: 2, failureThreshold: 3`). Readiness includes `db` (unchanged from slice 1), so a database outage takes the API out of the Gateway. That is intended, and the manual checklist demonstrates it.
  - `lifecycle.preStop.sleep.seconds: 10` (the Kubernetes sleep handler, KEP-3960; on by default in 1.36). The JRE image has no reason to carry `sleep`, and the handler needs no binary.
  - Service `api`: port 8080 named `http`, `targetPort: http`. **The management port is not in the Service.**
- `web.yaml`: Deployment and Service `web`, same pattern: `runAsUser: 101, runAsGroup: 101` (nginx-unprivileged), port `http` 8080, `/tmp` emptyDir (`sizeLimit: 64Mi`; pid and temp paths live there; the image's entrypoint scripts log that they cannot edit the read-only `conf.d` and continue), `terminationGracePeriodSeconds: 30`, `preStop` sleep 10 (the image's `STOPSIGNAL SIGQUIT` already makes nginx shut down gracefully), resources requests `cpu: 10m, memory: 32Mi`, limits `cpu: 200m, memory: 128Mi` (nginx starts one worker per visible CPU). Readiness `httpGet / :http` every 5 s; liveness `httpGet / :http` every 10 s; no startup probe.
- `postgres.yaml`: CNPG `Cluster` `shop-db` (`postgresql.cnpg.io/v1`): `instances: 1`, `imageName` = the operand pin above (with a Renovate comment), `bootstrap.initdb: {database: shop, owner: shop}` (creates secret `shop-db-app`), `storage.size: 1Gi` (default StorageClass, kind's local-path `standard`), `resources` requests `cpu: 100m, memory: 256Mi`, limit `memory: 512Mi`. Superuser access stays disabled (the default). CNPG's default pod security context (RuntimeDefault seccomp, non-root, no privilege escalation) passes `restricted`.
- `gateway.yaml`: Gateway `shop` (`gateway.networking.k8s.io/v1`), `gatewayClassName: envoy-gateway`, one listener `name: http, protocol: HTTP, port: 80, allowedRoutes.namespaces.from: Same`. HTTPRoute `shop` with `parentRefs: [{name: shop, sectionName: http}]`, no hostnames, rules: `PathPrefix /api` to `api:8080`, `PathPrefix /` to `web:8080`.
- `network-policies.yaml` (all `networking.k8s.io/v1`, namespace `shop`):

| Name | podSelector | Rule |
|---|---|---|
| `default-deny` | `{}` | `policyTypes: [Ingress, Egress]`, no rules |
| `allow-dns` | `{}` | egress to namespace `kube-system` pods `k8s-app: kube-dns`, UDP and TCP 53 |
| `web-from-gateway` | `name: web` | ingress TCP 8080 from namespace `envoy-gateway-system` pods with `gateway.envoyproxy.io/owning-gateway-name: shop` and `gateway.envoyproxy.io/owning-gateway-namespace: shop` (one `from` entry with both selectors) |
| `api-from-gateway` | `name: api` | same, for the API |
| `api-to-db` | `name: api` | egress TCP 5432 to pods `cnpg.io/cluster: shop-db` |
| `db-ingress` | `cnpg.io/cluster: shop-db` | ingress TCP 5432 from `name: api`; TCP 8000 and 5432 from namespace `cnpg-system` pods `app.kubernetes.io/name: cloudnative-pg` (CNPG docs: the operator must reach every instance on 8000 and 5432); TCP 5432 and 8000 from `cnpg.io/cluster: shop-db` (instances talk to each other; future replicas) |
| `db-egress` | `cnpg.io/cluster: shop-db` | egress TCP 6443 to `ipBlock 0.0.0.0/0` (the Kubernetes API; policy is evaluated after DNAT, so `10.96.0.1:443` becomes the control-plane node IP on 6443, and that IP is on Docker's dynamic `kind` subnet); TCP 5432 and 8000 to `cnpg.io/cluster: shop-db` |

The `cnpg.io/cluster` selector also covers the CNPG `initdb` job pod, which needs the same API access. Kubelet probes come from the host and Calico allows host-to-local-pod traffic, so probes need no rule. Namespaces outside `shop` get no policies in this slice.

`deploy/k8s/overlays/local/`:
- `kustomization.yaml`: `resources: [../../base, envoy-proxy.yaml]`; `images`: `shop-api` to `shop/api:dev`, `shop-web` to `shop/web:dev`; patches below.
- `envoy-proxy.yaml`: EnvoyProxy `shop-proxy` (`gateway.envoyproxy.io/v1alpha1`): `provider.type: Kubernetes`; `kubernetes.envoyService: {type: NodePort, patch: {type: StrategicMerge, value: {spec: {ports: [{port: 80, nodePort: 30080}]}}}}` (Service ports merge on `port`; the Service port equals the listener port 80); `kubernetes.envoyDeployment: {replicas: 1, container: {resources: {requests: {cpu: 50m, memory: 64Mi}, limits: {memory: 256Mi}}}}`.
- Patch Gateway `shop`: `spec.infrastructure.parametersRef: {group: gateway.envoyproxy.io, kind: EnvoyProxy, name: shop-proxy}`. NodePort exposure is local-only, so it lives in the overlay.
- Patch Deployment `api`: env `SPRING_PROFILES_ACTIVE=seed`; `imagePullPolicy: Never`; pod template annotation `shop.dev/image-id: api-image-id-placeholder`.
- Patch Deployment `web`: `imagePullPolicy: Never`; annotation `shop.dev/image-id: web-image-id-placeholder`.
- Replicas stay 1 (from base).

**Rolling `:dev` images.** `make deploy` replaces the placeholders with `docker image inspect -f '{{.Id}}'` of each local image before applying (`kubectl kustomize ... | sed ... | kubectl apply --server-side --field-manager=shop-deploy -f -`, in one `sh -c` inside the tools container, with IDs passed as env vars). A rebuilt image changes the pod template and rolls exactly once; an unchanged image is a no-op. `kubectl rollout restart` annotations are preserved because server-side apply does not own them. `Never` makes a missed `kind load` fail loudly (`ErrImageNeverPull`).

## API config (`services/api/src/main/resources/application.yaml`, default document)

- `spring.lifecycle.timeout-per-shutdown-phase: 20s`. Timing: preStop 10 s (Envoy drops the endpoint via EndpointSlice to xDS, typically under 2 s), then up to 20 s of Spring graceful drain, plus 10 s of slack for the 40 s grace period.
- `spring.flyway.connect-retries: 12` and `spring.flyway.connect-retries-interval: 10s`: on first deploy the API starts while CNPG is still initialising; retrying in-process avoids CrashLoopBackOff delays. Harmless for compose and tests.
- `server.forward-headers-strategy: native`. Not needed for slice 2 behaviour (nothing builds absolute URLs), and Boot would turn it on anyway when it detects Kubernetes. Set it explicitly so compose and cluster behave the same, and because slice 3's OAuth redirect URIs depend on it. Only Envoy and the e2e nginx can reach port 8080 (NetworkPolicy), so trusting `X-Forwarded-*` from internal addresses is safe.
- Verify the three property names against Boot 4.1 docs; existing tests must still pass at 100%.

## Makefile (PLATFORM)

New variables: `CLUSTER_NAME := shop`, `CLUSTER_PORT := 8088`, `CLUSTER_NODE_URL := http://shop-control-plane:30080`, `KIND_DIR := $(CACHE_DIR)/kind`, `K8S_TOOLS_IMAGE := shop/k8s-tools:dev`, `CLUSTER_REPORT_DIR := build/reports/cluster-e2e`. Helpers: `KIND` (socket, group, tools image), `K8S_RUN` (`--network kind`, `-i`, `KUBECONFIG=/cache/kind/kubeconfig`), `KUBECTL = $(K8S_RUN) kubectl`, `HELM = $(K8S_RUN) helm`. Export the new image variables. `cache-dirs` also creates `.cache/kind` and `.cache/kubeconform`. Targets with `##` help text:

- `k8s-tools`: builds the tools image (not in help).
- `cluster-up` (idempotent), depends on `k8s-tools`:
  1. If no kind cluster `shop` exists, `kind create cluster --config deploy/kind/cluster.yaml --image $(KIND_NODE_IMAGE) --kubeconfig /cache/kind/kubeconfig.external` (no `--wait`: the node is NotReady until Calico runs). If it exists but the node container is stopped, `docker start shop-control-plane` from the host shell.
  2. Write the internal kubeconfig (`umask 077`).
  3. `kubectl apply --server-side -k deploy/kind/calico`; wait for `ds/calico-node` and `deploy/calico-kube-controllers` rollouts in `kube-system`, `kubectl wait --for=condition=Ready node --all`, `deploy/coredns` rollout (300 s each).
  4. `helm upgrade --install eg oci://docker.io/envoyproxy/gateway-helm --version $(ENVOY_GATEWAY_CHART_VERSION) -n envoy-gateway-system --create-namespace -f deploy/kind/envoy-gateway-values.yaml --wait --timeout 5m`.
  5. `helm upgrade --install cnpg cloudnative-pg --repo https://cloudnative-pg.github.io/charts --version $(CNPG_CHART_VERSION) -n cnpg-system --create-namespace -f deploy/kind/cnpg-values.yaml --wait --timeout 5m`.
  6. `kubectl apply --server-side -f deploy/kind/gatewayclass.yaml`; wait for GatewayClass condition `Accepted`.
- `cluster-down`: `kind delete cluster --name shop`; `rm -rf .cache/kind`.
- `deploy`, depends on `build k8s-tools`: fail with "run make cluster-up first" if the cluster is missing; `kind load docker-image --name shop $(API_IMAGE) $(WEB_IMAGE)`; apply the overlay as described above, retrying up to 3 times 10 s apart (the CNPG webhook can refuse connections just after install); then wait: `cluster/shop-db` condition `Ready` (300 s), `rollout status deploy/api deploy/web` (300 s), `gateway/shop` condition `Programmed` (180 s); then `cluster-smoke`. On any failure, run `cluster-diagnostics` and exit non-zero. Prints `http://localhost:8088` at the end.
- `cluster-smoke` (no help line): from the host shell with bash `/dev/tcp` (no curl on the host), `GET /` and `GET /api/status` on `127.0.0.1:$(CLUSTER_PORT)` with `HTTP/1.1`, `Host: localhost:8088` and `Connection: close` (Envoy rejects HTTP/1.0). Retry 30 times 2 s apart until both status lines contain ` 200 `.
- `cluster-e2e`, depends on `e2e-deps k8s-tools`: recreate `$(CLUSTER_REPORT_DIR)`; run, collecting failures without stopping early: (1) `cluster-smoke`; (2) Playwright: `docker run --rm --init --shm-size 1g --network kind --user $(HOST_UID):$(HOST_GID) -e HOME=/cache/home -e CI=true -e E2E_BASE_URL=$(CLUSTER_NODE_URL) -e E2E_REPORT_DIR=/reports -v $(CURDIR)/tests/e2e:/e2e:ro -v $(CACHE_DIR)/home:/cache/home -v $(CURDIR)/$(CLUSTER_REPORT_DIR):/reports -w /e2e $(PLAYWRIGHT_IMAGE) ./node_modules/.bin/playwright test $(E2E_ARGS)`; (3) `$(K8S_RUN) sh tests/e2e/cluster/network-policy-test.sh`, output teed to `$(CLUSTER_REPORT_DIR)/network-policy.log`. On any failure, run `cluster-diagnostics`; exit with the combined status. Expects `cluster-up deploy` to have run; it does not deploy.
- `cluster-diagnostics` (help line: "Write cluster state and logs to build/reports/cluster-e2e"): every command `|| true`, into `$(CLUSTER_REPORT_DIR)/diagnostics/`: `kubectl get nodes,all,gateway,httproute,gatewayclass,envoyproxy,cluster.postgresql.cnpg.io,networkpolicy,pvc -A -o wide`; `kubectl get events -A --sort-by=.lastTimestamp`; `kubectl describe pods -n shop`; logs (`--all-containers --prefix --tail=2000`) for namespaces `shop`, `envoy-gateway-system`, `cnpg-system`, and `calico-node` in `kube-system`; `kind export logs`. Never `get secret` and never `-o yaml` on Secrets.
- `kubectl` (help line: `make kubectl ARGS="get pods -n shop"`): `$(KUBECTL) $(ARGS)`.
- `cluster-traffic` (help line: "Request /api/status every 100 ms through the gateway and print failures; Ctrl-C to stop"): `docker run --rm --network kind $(ALPINE_IMAGE) sh -c` with a busybox `wget -q -T 2 -O /dev/null $(CLUSTER_NODE_URL)/api/status` loop that prints a timestamped `FAIL` line per failed request and a count every 50 requests. Used by the manual rollout check.
- `k8s-lint`, added to `lint`: `docker run --rm --user ... -v $(CURDIR)/deploy:/deploy:ro $(KUBECTL_IMAGE) kustomize /deploy/k8s/overlays/local`, piped on the host into `docker run --rm -i --user ... -v $(CACHE_DIR)/kubeconform:/cache -v $(CURDIR):/work:ro -w /work $(KUBECONFORM_IMAGE) -strict -summary -kubernetes-version 1.36.4 -cache /cache -schema-location '<core>' -schema-location '<crd>' -` where core is `https://raw.githubusercontent.com/yannh/kubernetes-json-schema/$(K8S_SCHEMAS_REF)/{{.NormalizedKubernetesVersion}}-standalone{{.StrictSuffix}}/{{.ResourceKind}}{{.KindSuffix}}.json` and CRD is `https://raw.githubusercontent.com/datreeio/CRDs-catalog/$(CRD_SCHEMAS_REF)/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json`. A second kubeconform run validates `deploy/kind/gatewayclass.yaml` and `tests/e2e/cluster/network-policy-probes.yaml`. The Calico kustomization (third-party, remote) is not linted. With `-cache`, the first run downloads the schemas and later runs work offline; the CI deps cache gains `.cache/kubeconform`. Derive `-kubernetes-version` from `KIND_NODE_IMAGE` with `sed` so there is one source.
- `docker-lint` adds `deploy/k8s-tools/Dockerfile`.
- `clean`: if `docker ps -aq --filter label=io.x-k8s.kind.cluster=shop` is non-empty, `$(MAKE) cluster-down`; then the existing steps.
- `deploy/versions.mk` gains, each with a `# renovate:` annotation: `KIND_VERSION` (`datasource=go depName=sigs.k8s.io/kind`), `KIND_NODE_IMAGE` (`datasource=docker depName=kindest/node`), `KUBECTL_IMAGE`, `HELM_VERSION` (`datasource=go depName=helm.sh/helm/v4`), `KUBECONFORM_IMAGE`, `GOLANG_IMAGE`, `DOCKER_CLI_IMAGE`, `ENVOY_GATEWAY_CHART_VERSION` (`datasource=docker depName=docker.io/envoyproxy/gateway-helm`), `CNPG_CHART_VERSION` (`datasource=helm depName=cloudnative-pg registryUrl=https://cloudnative-pg.github.io/charts`). `K8S_SCHEMAS_REF` and `CRD_SCHEMAS_REF` get a plain comment instead ("update by hand together with the node image and chart versions") because the CRD schemas must match the chart versions.

## CI (`.github/workflows/ci.yaml`)

- `ci` job: add `.cache/kubeconform` to the deps cache paths; nothing else changes.
- New job `cluster-e2e`, parallel to `ci` (no `needs`): `runs-on: ubuntu-24.04`, `timeout-minutes: 35`, `permissions: {contents: read}`. Steps:
  1. `actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1` with `persist-credentials: false`.
  2. `actions/cache/restore@55cc8345863c7cc4c66a329aec7e433d2d1c52a9 # v6.1.0` with exactly the `ci` job's deps paths, key and restore-keys (restore only, so the two jobs never race to save).
  3. `run: make cluster-up deploy cluster-e2e`.
  4. `actions/upload-artifact@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7.0.2`, `if: failure()`, name `cluster-e2e-reports`, path `build/reports/cluster-e2e`, `if-no-files-found: ignore`, `retention-days: 7`.
- No expressions from event payloads in `run:` steps. Must pass `make workflow-lint` (actionlint and zizmor).
- Estimated cold runtime about 15 minutes: tools image (Go compile) 2 to 3, image builds 4 to 5, cluster and Calico 2, charts 2, deploy 2, tests 2. Record the measured time in STATUS.md after the first run; cache the tools image only if the run exceeds 20 minutes.

## Renovate (`renovate.json`)

- Extend the `versions.mk` regex with an optional ` registryUrl=(?<registryUrl>\S+)` group after `depName`. Existing entries must still match; run `make renovate-validate`.
- New custom regex manager for `deploy/k8s/**/*.yaml` and `deploy/kind/**/*.yaml`: a `# renovate: datasource=docker depName=<name>` comment followed by an `imageName:` line (tag plus digest).
- New custom regex manager for `deploy/kind/calico/kustomization.yaml`: `projectcalico/calico/(?<currentValue>v[\d.]+)/manifests`, `datasource=github-releases`, `depName=projectcalico/calico`.
- Package rules, after the non-major group so they win:
  - `kindest/node`: `enabled: false`. Description: the digest must come from the kind release notes; bump it by hand with `KIND_VERSION`.
  - `registry.k8s.io/kubectl`: `allowedVersions: "<1.37"`. Description: stay within the node's minor; raise it together with the node image.
  - `ghcr.io/cloudnative-pg/postgresql`: `versioning: "regex:^(?<major>\\d+)\\.(?<minor>\\d+)-(?<patch>\\d{12})-(?<compatibility>\\S+)$"`, `allowedVersions: "<19"` (a major upgrade needs a planned `pg_upgrade`).
  - Group `cluster platform`: `sigs.k8s.io/kind`, `helm.sh/helm/v4`, `docker.io/envoyproxy/gateway-helm`, `cloudnative-pg`, `ghcr.io/cloudnative-pg/postgresql`, `projectcalico/calico`, `registry.k8s.io/kubectl`, `ghcr.io/yannh/kubeconform`. Keeps cluster-breaking updates out of the general non-major PR; the `cluster-e2e` job gates them.

## End-to-end (E2E+DOCS)

- **Existing specs run unchanged against the cluster.** None depends on compose: `outage.spec.ts` simulates both outages in the browser with `page.route` and stops no container. Nothing is skipped.
- **Base URL.** Playwright runs on the `kind` network against `http://shop-control-plane:30080`: the same NodePort, Envoy and HTTPRoute path the host uses, minus Docker's 127.0.0.1 forward. Host networking is not used (it behaves differently on Docker Desktop). The real front door `127.0.0.1:8088` is covered by `cluster-smoke`. Known limit, for the ADR: the browser origin in cluster e2e is not `localhost`, so it is not a secure context. Slice 3 (Secure `__Host-` cookies over http) must deal with that, for example with Chromium's `--unsafely-treat-insecure-origin-as-secure`.
- **New spec `tests/e2e/specs/routing.spec.ts`** (runs in both stacks): `GET /actuator/health` returns the SPA (`content-type` contains `text/html`) and not health JSON; `GET /api/actuator/health` returns 404. This proves the management port is not exposed.
- **NetworkPolicy test**: `tests/e2e/cluster/network-policy-probes.yaml` plus `tests/e2e/cluster/network-policy-test.sh` (POSIX `sh`, run from `/work` in the tools container, `kubectl` on PATH, repo read-only).
  - Three Pods in `shop`, all labelled `shop.dev/netpol-probe: "true"`: `netpol-probe-api` (`app.kubernetes.io/name: api`), `netpol-probe-web` (`app.kubernetes.io/name: web`), `netpol-probe-other` (`app.kubernetes.io/name: netpol-probe`). None carries `app.kubernetes.io/part-of`, so no Service selects them. Image placeholder `PROBE_IMAGE`; the script replaces it with `kubectl get cluster.postgresql.cnpg.io shop-db -n shop -o jsonpath='{.spec.imageName}'` and pipes the result to `kubectl apply -f -`. The operand image is already on the node and has `bash` and `timeout` (Debian essentials).
  - Restricted-compliant: `automountServiceAccountToken: false`, `runAsNonRoot: true`, `runAsUser: 26`, `runAsGroup: 26` (the image's postgres UID; a named image user would fail the non-root check), `seccompProfile: RuntimeDefault`, `allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`, `readOnlyRootFilesystem: true`, command `["sleep", "600"]`, `terminationGracePeriodSeconds: 1`, small requests and limits.
  - Checks, via `kubectl exec <pod> -- timeout 5 bash -c 'exec 3<>/dev/tcp/<host>/<port>'` (exit 0 means connected): api to `shop-db-rw:5432` **must connect**; web to `shop-db-rw:5432`, other to `shop-db-rw:5432`, web to `api:8080` and api to `web:8080` **must fail**. The allowed check proves the database and DNS work, so the denials come from policy. Print one `PASS` or `FAIL` line per check with the exit code; delete the probes in an `EXIT` trap (`kubectl delete pod -l shop.dev/netpol-probe=true --wait=false`); exit non-zero on any failure. Wait for the probes with `kubectl wait --for=condition=Ready --timeout=90s`.
- Playwright config, existing specs and `tests/e2e/compose.yaml` stay as they are. `make e2e` behaviour is unchanged.

## Docs (E2E+DOCS)

- `docs/adr/0005-local-kubernetes-on-kind.md`: context (slice 2, WSL laptop and CI, memory); decisions: kind with one node and the 1.36 node image (support matrix); Calico instead of kindnet (WSL kernel lacks `NFT_QUEUE`, kindnet fails open, kindnet accepts all UDP 53); Envoy Gateway with Gateway API v1 and a NodePort fixed through EnvoyProxy, `127.0.0.1:8088`; no TLS or HSTS (localhost is a secure context; TLS and HSTS belong at the Gateway in the cloud guide); CNPG minimal operand image and the `-app` secret; Kustomize for the app, Helm only for third-party charts; tools image built with `go install` (sumdb-verified) and why kind and Helm have no official images; kubectl and Helm on the `kind` network with the internal kubeconfig (no host networking); kubeconform with pinned schema commits; image-ID annotation for `:dev` rollouts; the probe and shutdown timing (10 + 20 + 10 = 40 s); the NetworkPolicy table and its test; restricted Pod Security; the `cluster-e2e` CI job; Playwright via the node hostname and the slice 3 implication; consequences and limits.
- `README.md`: new section "Run on Kubernetes (kind)": `make cluster-up deploy`, open `http://localhost:8088`, `make cluster-e2e`, `make kubectl ARGS=...`, `make cluster-down`; first run downloads about 1.5 GB of images; memory footprint about 2.5 GB with everything running (measure with `docker stats shop-control-plane --no-stream` and record the number); new targets in the Targets table; layout additions (`deploy/k8s`, `deploy/kind`, `deploy/k8s-tools`, `tests/e2e/cluster`). Known limits: no TLS or HSTS on localhost; one node, one Postgres instance, data lost on `cluster-down`; `make deploy` uses local `:dev` images (registry and digests in slice 6); after a Docker Desktop restart the cluster may not recover, so run `make cluster-down cluster-up deploy`; port 8088 must be free; cluster e2e uses the node hostname, not localhost.
- `tests/README.md`: rows for "Cluster end-to-end" (`make cluster-e2e`, all Playwright specs against Envoy in kind) and "NetworkPolicy" (allowed and denied connections from restricted probe pods); a short "How the cluster e2e works" section; debugging: `build/reports/cluster-e2e/` (Playwright HTML, `network-policy.log`, `diagnostics/`), `make cluster-diagnostics`.
- `tests/manual/README.md`, new section "Kubernetes (kind)":
  1. `make cluster-up deploy`; browse `http://localhost:8088` (catalog, product, `/status`); CSP shows no violations in dev tools.
  2. Run `make deploy` again with nothing changed: pod ages stay the same (`make kubectl ARGS="-n shop get pods"`). Change a visible string in the web app, `make deploy`: only `web` rolls. Revert.
  3. Zero-drop rollout: terminal A `make cluster-traffic`; terminal B `make kubectl ARGS="-n shop rollout restart deploy/api"` then `... rollout status deploy/api`; A prints no `FAIL`. Repeat for `web`.
  4. Database outage: `make kubectl ARGS="-n shop delete pod shop-db-1"`; `/status` shows the API unavailable, then recovers without a reload once CNPG restarts the instance.
  5. Pod Security: `make kubectl ARGS="-n shop run pss-check --image=busybox --restart=Never -- id"` is rejected by `restricted`.
  6. `make kubectl ARGS="-n shop get networkpolicy"` lists the seven policies; `make kubectl ARGS="get pods -A"` shows Calico, Envoy Gateway, CNPG.
  7. Memory: `docker stats shop-control-plane --no-stream`; compare with the README figure.
  8. `make cluster-down`; `docker ps` shows no `shop-control-plane`.
  9. After the first PR run: the `cluster-e2e` job passed; record its duration in STATUS.md.

## Work split (three Sonnet implementers in parallel; no shared files)

| Agent | Owns |
|---|---|
| K8S | `deploy/k8s/**`, `deploy/kind/**` (cluster config, Calico kustomization, Helm values, GatewayClass) |
| PLATFORM | `Makefile`, `deploy/versions.mk`, `deploy/k8s-tools/Dockerfile`, `.github/workflows/ci.yaml`, `renovate.json`, `services/api/src/main/resources/application.yaml` |
| E2E+DOCS | `tests/e2e/**` (routing spec, `cluster/` probes and script), `docs/adr/0005-local-kubernetes-on-kind.md`, `README.md`, `tests/README.md`, `tests/manual/README.md` |

Fixed interfaces (nobody changes these without the orchestrator):
- Cluster `shop`, node container and hostname `shop-control-plane`, Docker network `kind`, host front door `127.0.0.1:8088`, NodePort 30080, in-network URL `http://shop-control-plane:30080`.
- Namespaces: `shop` (app), `envoy-gateway-system`, `cnpg-system`, `kube-system` (Calico, CoreDNS). Helm releases `eg` and `cnpg`.
- Objects: Deployments and Services `api` and `web` (port 8080 `http`; API management 8081 container-only), CNPG Cluster `shop-db`, Service `shop-db-rw`, secret `shop-db-app`, Gateway `shop` (listener `http`, port 80), HTTPRoute `shop`, GatewayClass `envoy-gateway`, EnvoyProxy `shop-proxy`.
- Labels: as in the Manifests section; probe label `shop.dev/netpol-probe: "true"`; image annotation `shop.dev/image-id` with placeholders `api-image-id-placeholder` and `web-image-id-placeholder`.
- Paths: `deploy/kind/cluster.yaml`, `deploy/kind/calico/`, `deploy/kind/envoy-gateway-values.yaml`, `deploy/kind/cnpg-values.yaml`, `deploy/kind/gatewayclass.yaml`, `deploy/k8s/overlays/local`, `tests/e2e/cluster/network-policy-test.sh`, `tests/e2e/cluster/network-policy-probes.yaml` (placeholder `PROBE_IMAGE`).
- Env for scripts in the tools container: `KUBECONFIG=/cache/kind/kubeconfig`, working directory `/work` (repo root, read-only).
- Make targets: `cluster-up`, `cluster-down`, `deploy`, `cluster-e2e`, `cluster-smoke`, `cluster-diagnostics`, `cluster-traffic`, `kubectl` (with `ARGS`), `k8s-lint`, `k8s-tools`; `E2E_ARGS` works for `cluster-e2e`.

Each agent verifies its own part in containers: K8S runs `kubectl kustomize` and kubeconform through `docker run` with the pinned images, plus `make cluster-up deploy` once PLATFORM's targets exist (coordinate through the orchestrator); PLATFORM runs `make lint`, `make k8s-tools`, `make renovate-validate`; E2E+DOCS runs `make e2e-lint` and `make e2e E2E_ARGS=specs/routing.spec.ts`. Then the orchestrator runs `make lint`, `make ci`, `make cluster-up deploy cluster-e2e`, a second `make deploy` (expects no restarts), and `make cluster-down`.

## Out of scope

HPA, PDBs of our own, metrics-server, observability, TLS and HSTS, Valkey and Mailpit in the cluster, Argo CD, image registry, signing and digests for app images, multi-replica Postgres and backups, a cloud overlay, NetworkPolicies outside `shop`, zero-drop rollout as an automated test (manual in this slice).

## Known traps

- **Gateway API kinds.** Gateway, HTTPRoute and GatewayClass are `gateway.networking.k8s.io/v1` (not `v1beta1`). EnvoyProxy is `gateway.envoyproxy.io/v1alpha1`. `Gateway.spec.infrastructure.parametersRef` is a local reference (`group`, `kind`, `name`; no namespace); with it, `mergeGateways` must stay off.
- **Envoy ports.** The listener port 80 becomes container port 10080 and Service port 80; the NodePort patch matches on `port: 80`. The Envoy Service defaults to `externalTrafficPolicy: Local`, which is fine on one node.
- **Envoy proxy labels** (v1.10 source): `app.kubernetes.io/{name: envoy, component: proxy, managed-by: envoy-gateway}`, `gateway.envoyproxy.io/owning-gateway-{name,namespace}`. The proxy runs in `envoy-gateway-system`, not in `shop`.
- **Helm 4.** `--wait` takes a strategy (bare `--wait` means `watcher`); `--atomic` is now `--rollback-on-failure` (do not use it); fresh installs use server-side apply. OCI chart versions keep the `v` (`v1.10.0`); the CNPG chart does not (`0.29.1`). `helm version` from a `go install` build prints a short version; that is expected.
- **CNPG.** The field is `spec.imageName` (not `image`). The app secret keys are `username`, `password`, `jdbc-uri` and others; the Service is `shop-db-rw`; the condition is `Ready`; the pod label is `cnpg.io/cluster`; the first pod is `shop-db-1`, with an `initdb` job pod before it. The operator's webhook may refuse connections for a few seconds after `helm --wait` (hence the apply retry). CNPG reads the Postgres major version from the tag, so keep the tag next to the digest.
- **Calico.** It lives in `kube-system` (manifest install, not the operator). Without the CIDR env it creates a `192.168.0.0/16` pool that does not match the kind pod subnet. It enforces UDP DNS, so `allow-dns` is load-bearing. Images come from `quay.io/calico/*:v3.32.3` (pulled by the node, tag only).
- **kube API egress.** Policy is evaluated after DNAT: allow TCP 6443 to the node, not 443 to `10.96.0.1`.
- **Pod Security `restricted`.** Every container needs `runAsNonRoot`, seccomp `RuntimeDefault`, `allowPrivilegeEscalation: false` and `capabilities.drop: [ALL]`. Images whose user is a name need an explicit numeric `runAsUser`. Bare `kubectl run` pods are rejected; that is the manual check.
- **Kustomize in kubectl 1.36.** Use `patches` (not `patchesStrategicMerge`) and `labels` or explicit labels (not `commonLabels`, which rewrites selectors). Remote resources (Calico) need network access on every apply.
- **kind and images.** `kindest/node` must be the digest listed for this kind release. `kind load` stores `shop/api:dev` as `docker.io/shop/api:dev`; the pod's `shop/api:dev` resolves to the same name. `kind load` skips images whose ID is already on the node.
- **kubeconfig.** It holds cluster-admin credentials: `.cache/kind` only, mode 600, never printed, never in diagnostics or artifacts.
- **Trivy.** `make scan` (`scan-fs`, misconfig scanner) reads the new YAML; it must stay free of HIGH/CRITICAL findings. The securityContext above is designed for that; fix findings, do not add ignores without asking.
- **Docker Desktop on WSL.** The WSL kernel lacks `NFT_QUEUE` (the reason for Calico); if the NetworkPolicy test passes in CI but fails locally, check `calico-node` logs first. Ports published on `127.0.0.1` reach WSL through Docker Desktop's forwarding (the smoke check relies on it). A Docker Desktop restart or Resource Saver pause can leave the node unhealthy: `make cluster-down cluster-up deploy`. Watch the 12 GB WSL limit when `make dev`, `make e2e` and the cluster run at once.
- **Renovate timestamps.** As noted in slice 0, registries without release timestamps (MCR, some ghcr images, Docker Hub images Renovate cannot date) can be held by the 7-day gate. Treat new images the same way as existing ones; do not add `timestamp-optional` rules without the owner.
- **Spring Boot 4.1.** Verify `spring.lifecycle.timeout-per-shutdown-phase`, `spring.flyway.connect-retries`, `spring.flyway.connect-retries-interval` and `server.forward-headers-strategy` in the Boot 4.1 reference; Flyway configuration moved to its own module in Boot 4.

# Amendments after the design challenge (these OVERRIDE anything above that conflicts)

The challenger ran checks on the owner's machine (Docker Desktop 29.9.3, containerd image store, WSL kernel 5.15.167.4) on 2026-11-17. All 11 findings accepted.

- **B1 (blocker) Reproducible image IDs.** With the containerd store, `docker build` adds a provenance attestation, so `.Id` is an index digest that changes on every rebuild even from full cache. `api-build` and `web-build` pass `--provenance=false --sbom=false` (attestations arrive with the registry in slice 6). PLATFORM verifies that two `make build` runs print identical `docker image inspect -f '{{.Id}}'` for both images; the orchestrator verifies that a second `make deploy` leaves pod ages unchanged. The Known traps claim "`kind load` skips images whose ID is already on the node" is wrong on a containerd store (kind re-imports every time; slower, harmless); ignore it.
- **B2 Front-door origin in every e2e run.** Playwright's browser uses `baseURL` `http://localhost:8088` in both stacks with `launchOptions.args: ['--host-resolver-rules=MAP localhost:8088 ' + E2E_UPSTREAM]`. `E2E_UPSTREAM` is `shop-control-plane:30080` for the cluster and `gateway:8080` for compose (verify a hostname works as the mapping target; if not, resolve it to an IP and pass the IP). The Node-side `request` fixture does not use Chromium's resolver: it gets `baseURL` `http://${E2E_UPSTREAM}` and `extraHTTPHeaders: { Host: 'localhost:8088' }`. Add one assertion that `page.evaluate(() => isSecureContext)` is true. Drop every mention of `--unsafely-treat-insecure-origin-as-secure` (the headless shell ignores it, and `__Host-` cookies are still rejected with full Chromium). Owner: E2E+DOCS (`playwright.config.ts`, `tests/e2e/compose.yaml` env); PLATFORM passes `E2E_UPSTREAM` from `cluster-e2e`. For the ADR, as a slice 3 note: `tests/e2e/gateway.conf` sends `Host: $proxy_host` to the API, while Envoy keeps the original Host.
- **B3 NetworkPolicy test exercises ingress and cannot pass vacuously.** A denial passes only on exit code 124 (timeout; Calico drops, it does not reject); any other non-zero exit fails the test. Run `getent hosts <host>` first and fail if it does not resolve. Add namespace `netpol-probe` (restricted PSS labels, no policies) with a restricted-compliant pod `netpol-probe-outside` whose egress is open; from it `api.shop:8080`, `shop-db-rw.shop:5432` and `web.shop:8080` must be denied, and TCP `kube-dns.kube-system:53` must connect (positive control). The script's trap deletes the namespace. Total: 2 allowed and 7 denied checks; the Goal line changes accordingly.
- **B4 Report mount.** The `KIND` and `K8S_RUN` helpers also mount `$(CURDIR)/$(CLUSTER_REPORT_DIR)` read-write at `/reports` so `kind export logs` can write; kubectl output is redirected on the host side.
- **B5 kube-proxy mode.** The kind config sets `networking.kubeProxyMode: iptables` explicitly (the WSL kernel lacks `CONFIG_NFT_FIB_IPV4`).
- **B6 Calico by commit.** Reference the Calico manifest by commit SHA (`.../calico/<sha>/manifests/calico.yaml`), with the version in a comment for Renovate.
- **B7 Renovate details.** registry.k8s.io and quay.io give no release timestamps, so the 7-day gate holds kubectl updates indefinitely; say so in the package rule description (no `timestamp-optional` without the owner). The YAML regex manager allows leading indentation before `# renovate:` and `imageName:` and captures `currentDigest`.
- **B8 `cluster-down` without the tools image.** `cluster-down` removes the containers labelled `io.x-k8s.kind.cluster=shop` with plain `docker` (tolerating none) and deletes the kubeconfig under `.cache/kind`, so `make clean` does not build Go.
- **B9 Idempotent `cluster-up`.** Skip a Helm release when `helm list -n <ns> -o json` already shows the pinned chart version deployed.
- **B10 IPv6 on the kind network.** Verify that `cluster-traffic`'s busybox `wget` reaches the NodePort; if it picks the node's IPv6 address and fails, use the node's IPv4 address from `docker inspect`.
- **B12 Corrections from implementation and review (the code is right where this brief differs).** B9's skip also compares a stamp holding the chart version and the sha256 of the values file. Calico is tracked with `datasource=github-tags`, capturing both the version comment and the 40-hex commit digest in the URL. CI has a "Collect cluster diagnostics" step, and the upload runs on `failure() || cancelled()`. The first kubeconform run in `k8s-lint` reads stdin and needs no `/work` mount. The browser origin in cluster e2e is `http://localhost:8088` (B2), not the node hostname. `clean` always runs the plain-docker `cluster-down` (B8). The kind config also sets `failCgroupV1: false` (cgroup v1 on Docker Desktop here), and Calico drops the `sys-kernel-security` mount (read-only sysfs).
- **B11 Prettier.** `tests/e2e/cluster/network-policy-probes.yaml` and any new YAML under `tests/e2e` must pass `prettier --check` (single quotes, as in `compose.yaml`).
