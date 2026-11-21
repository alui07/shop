# ADR 0005: Local Kubernetes on kind

Status: accepted, 2026-11-17

## Context

Slice 2 runs slice 1 in Kubernetes: Envoy Gateway in front, Postgres from CloudNativePG, restricted Pod Security, default-deny NetworkPolicies, and the same Playwright suite. It has to work on a WSL2 laptop with Docker Desktop and 12 GB of memory, and on a GitHub runner, with the same commands in both. Nothing is installed on the host (ADR 0001).

## Decisions

**kind, one node, Kubernetes 1.36.** One control-plane node that also runs workloads keeps memory near 2.5 GB. The node image is pinned by tag and digest to the release listed for the pinned kind version (its support matrix).

**Calico instead of kindnet.** kindnet enforces NetworkPolicy through nftables `queue`, fails open, and always accepts UDP 53. The WSL2 kernel has no `CONFIG_NFT_QUEUE`, so on the laptop policies would silently not apply. The cluster sets `disableDefaultCNI` and installs Calico (iptables data plane, no IPIP) on every machine, so laptop and CI enforce the same way. The manifest is referenced by commit SHA. kube-proxy runs in `iptables` mode because the kernel also lacks `CONFIG_NFT_FIB_IPV4`. The NetworkPolicy test (below) is what proves enforcement on each machine.

**cgroup v1 on the laptop.** The owner's Docker Desktop is cgroup v1, which kubelet 1.36 refuses by default. `deploy/kind/cluster.yaml` sets `failCgroupV1: false` in the kubelet configuration; it does nothing on cgroup v2, which CI uses. Kubernetes treats cgroup v1 as deprecated, so this setting is a stopgap until Docker Desktop moves to v2.

**Envoy Gateway with Gateway API v1.** GatewayClass `envoy-gateway`, Gateway `shop` (HTTP, port 80) and HTTPRoute `shop` replace the nginx stand-in that `make e2e` uses. An EnvoyProxy resource fixes the Service as a NodePort (30080), and kind maps it to `127.0.0.1:8088` on the host.

**No TLS or HSTS locally.** `http://localhost:8088` is a secure context, so browser features that need one work. TLS and HSTS belong on the Gateway in the cloud setup.

**CloudNativePG, minimal operand image.** One-instance `Cluster` `shop-db`. The operator creates the `shop-db-app` secret and the `shop-db-rw` Service; the API reads its datasource from that secret. The operand image is pinned by tag and digest (CNPG derives the Postgres major from the tag).

**Kustomize for our app, Helm only for third-party charts.** `deploy/k8s` is plain Kustomize (base and a `local` overlay). Envoy Gateway and CNPG come from their charts with values files in `deploy/kind`. Calico is a remote manifest.

**Tools image built with `go install`.** kind and Helm publish no official container images. `shop/k8s-tools:dev` builds them from source with `go install`; the Go module proxy and checksum database verify every module, so no hand-kept hashes are needed and Renovate bumps one version. kubectl and the Docker CLI are copied from pinned images. Tools run on the `kind` Docker network with an internal kubeconfig, so no host networking is used (it behaves differently on Docker Desktop). The kubeconfig holds cluster-admin credentials and stays in `.cache/kind`.

**Docker socket trade-off.** kind needs the Docker daemon, so only the kind container gets the Docker socket (cluster create, image load and log export, in `cluster-up`, `deploy` and `cluster-diagnostics`). kubectl and Helm reach the cluster over the `kind` network without it. The socket is root-equivalent on the host. It extends the trade-off in ADR 0001 (accepted for local development and ephemeral CI runners) and is limited to the kind container in those targets; lint, test and scan targets still never get it.

**Idempotent `cluster-up`.** A Helm release is applied again only when its chart version or its values differ from what is deployed, so a second run changes nothing and restarts nothing.

**kubeconform with pinned schema commits** validates the rendered manifests, including Gateway API, Envoy Gateway and CNPG kinds, in `make k8s-lint`.

**`:dev` images and rollouts.** Images are loaded with `kind load`. Because the tag never changes, each Deployment carries a `shop.dev/image-id` annotation set from the image ID, so a changed image rolls the pods and an unchanged one does not. This needs reproducible image IDs: the builds pass `--provenance=false --sbom=false`, since attestations would change the ID on every build. Registry and digests arrive in slice 6.

**Probe and shutdown timing.** A pod stops receiving traffic and still serves it for a while: preStop sleep 10 s, then Spring's graceful shutdown 20 s, plus 10 s of margin, which gives a termination grace period of 40 s (10 + 20 + 10). This is what keeps rollouts free of dropped requests; `make cluster-traffic` checks it by hand.

**NetworkPolicies (namespace `shop`).** Default deny for ingress and egress, then explicit allows:

| Policy | Allows |
|---|---|
| `default-deny` | nothing, in both directions, for every pod |
| `allow-dns` | all pods to CoreDNS on UDP and TCP 53 |
| `web-from-gateway`, `api-from-gateway` | TCP 8080 into `web` and `api` from the Envoy proxy pods of Gateway `shop` only |
| `api-to-db` | `api` to Postgres on 5432 |
| `db-ingress` | Postgres from `api` on 5432, from the CNPG operator on 8000 and 5432, from other instances |
| `db-egress` | Postgres to the Kubernetes API (TCP 6443 to `0.0.0.0/0`, because the kind node's address is assigned by Docker and differs between machines; policy is evaluated after DNAT) and to other instances |

Namespaces outside `shop` get no policies; slice 5a adds `api-to-otel-collector` (egress from `api` to the collector in `obs`, TCP 4318) in the `otel` component, and `obs` itself has none (threat model R55).

`tests/e2e/cluster/network-policy-test.sh` runs 2 connections that must work and 7 that must be dropped, from probe pods that satisfy restricted Pod Security. Probes in `shop` carry the labels of `api`, `web` and an unrelated app. A probe in a separate namespace `netpol-probe` (no policies) must be blocked from `api`, `web` and the database, which exercises ingress rules. Each check first resolves the name with `getent`, and a denial only passes on exit code 124 (timeout, since Calico drops packets). A refused connection or any other error fails the test, so it cannot pass by accident. Positive controls: `api` reaches the database, and the outside probe reaches `kube-dns`.

**Restricted Pod Security** is enforced on `shop` through namespace labels. All containers run as non-root with a numeric UID, `RuntimeDefault` seccomp, no privilege escalation and all capabilities dropped.

**CI.** A separate `cluster-e2e` job runs in parallel with `ci`. `make ci` stays compose-based.

**Playwright on the front-door origin.** In both stacks the browser opens `http://localhost:8088`, a secure context, and Chromium maps that host to the real upstream with `--host-resolver-rules` (`shop-control-plane:30080` for the cluster, `gateway:8080` for Compose). The Node-side `request` fixture does not use Chromium's resolver, so it connects to the upstream directly and sends `Host: localhost:8088`. One spec asserts `isSecureContext`. The real host path `127.0.0.1:8088` is covered by `make cluster-smoke`. Slice 3 notes:
- `__Host-` cookies need this secure origin.
- `tests/e2e/gateway.conf` sends `Host: $proxy_host` to the API while Envoy keeps the original Host, so Host-dependent behaviour can differ between the stacks.
- `server.forward-headers-strategy: native` trusts `X-Forwarded-*` from internal addresses, and on kind the client address is internal. Before OAuth redirects depend on those headers, add an Envoy ClientTrafficPolicy or strip incoming forwarded headers.
- Envoy's `PathPrefix /api` also matches a bare `/api`. The Compose gateway is aligned (`location ~ ^/api(/|$)`), so both stacks send `/api` and `/api/...` to the API and `/apiary` to the web app.

## Slice 5a additions (observability, autoscaling)

**metrics-server.** `cluster-up` installs it with Helm into `kube-system` (chart and image pinned in `deploy/versions.mk`, the image by digest, values `deploy/kind/metrics-server-values.yaml` with only `--kubelet-insecure-tls`, because kind's kubelet certificates are self-signed) and waits for the APIService `v1beta1.metrics.k8s.io`. A spike on the cgroup v1 node gave the first numbers about 32 s after install and scaled a test HPA from 1 to 3 pods in 64 s, so cgroup v1 is no obstacle.

**Two API replicas and an HPA (D32).** `base` runs the API under HPA `api`: 2 to 4 replicas at 60% CPU of the 250m request, scale-up stabilisation 60 s (ignores the JIT spike after a pod turns ready; a sustained load still scales) and scale-down 120 s. The web app runs 2 fixed replicas. `cluster-e2e` therefore runs against two pods of each, which exercises the multi-replica paths ADR 0009 could only test in integration. `deploy` waits after the rollout until `deployment/api` has as many ready replicas as the HPA's `minReplicas`. `cluster-e2e` samples the HPA while it runs and writes a replica summary and the `SuccessfulRescale` events to `build/reports/cluster-e2e/hpa.log`.

**The HPA owns `replicas`.** `api.yaml` has no `replicas`, so server-side apply does not reset the HPA's count on every `make deploy`; a second deploy keeps it. On a cluster applied by an older branch, `shop-deploy` owned `.spec.replicas` (1): the first 5a apply drops the field, it defaults to the current 1 (no drop) and the HPA raises it to 2. Going back to a branch from before 5a then fails with a field conflict on `replicas` (no `--force-conflicts`): recreate the cluster with `make cluster-down cluster-up deploy`.

**`<unknown>` window.** A new target pod reads `cpu: <unknown>` for about 45 s, with `FailedGetResourceMetric` and `FailedComputeMetricsReplicas` warning events, until metrics-server has its first sample. That is expected; the readiness wait and the reports tolerate it and nothing asserts the absence of HPA warnings.

**PodDisruptionBudgets.** `api` and `web`, `maxUnavailable: 1` (`deploy/k8s/base/pdb.yaml`). Keycloak, Valkey and Mailpit have a single replica, where a budget would block a drain forever; CNPG manages its own. The `api` and `web` budgets can also stall `kubectl drain` on this single-node cluster: the drained node is cordoned, so evicted replicas have nowhere to be rescheduled and the budget refuses the next eviction until a replacement is ready. `tests/e2e/cluster/pdb-test.sh`, run by `cluster-e2e` after Playwright, evicts one API pod through the Eviction API (accepted), immediately evicts another (HTTP 429 from the PDB), waits for the API to recover and checks that the metrics API serves `shop` pods.

**Grafana on the shop Gateway.** The `http` listener's `allowedRoutes` selects the namespaces `shop` and `obs`; `make obs-up` adds an HTTPRoute `grafana` in `obs` for host `grafana.localhost`, so Grafana is at `http://grafana.localhost:8088` with no new host port and no cluster recreate. See ADR 0010.

**cgroup v1 locally, v2 in CI.** Host facts on the owner's machine (2026-11-21): 15.6 GiB, 12 CPUs, cgroup v1, no `.wslconfig` (WSL's default of half the host memory applies). CI is cgroup v2. The metrics-server and `pdb-test.sh` behaviour on cgroup v2 is unverified until the first pull request run.

## Consequences

- The laptop and CI run the same cluster configuration, and policy enforcement is tested, not assumed. They differ in cgroup version (see below).
- Limits: the laptop needs `failCgroupV1: false` (cgroup v1); one node, one Postgres instance, data lost on `cluster-down`; local `:dev` images only; no TLS; after a Docker Desktop restart the cluster may not recover (`make cluster-down cluster-up deploy`); the cluster needs about 3.7 GiB with two API pods and everything else running, and obs adds about 0.8 GiB of requests; going back to a pre-5a branch needs a cluster recreate (HPA ownership of `replicas`).
- Calico is tracked through `github-tags`, which has release timestamps, and Renovate updates both the version comment and the commit SHA. The kubectl image (registry.k8s.io) and the CNPG operand images have no release timestamps, so the 7-day Renovate age gate holds their updates until someone looks.
