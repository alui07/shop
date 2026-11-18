#!/bin/sh
# Proves the NetworkPolicies enforce: 2 connections must work, 7 must be dropped.
# Run from the repo root in the k8s tools container (kubectl on PATH, KUBECONFIG set).
set -u

probes=tests/e2e/cluster/network-policy-probes.yaml
failures=0

cleanup() {
  kubectl delete namespace netpol-probe --wait=false --ignore-not-found >/dev/null 2>&1
  kubectl delete pod -n shop -l shop.dev/netpol-probe=true --wait=false --ignore-not-found >/dev/null 2>&1
}
trap cleanup EXIT
trap 'exit 130' INT TERM

report() {
  verdict=$1 label=$2 detail=$3
  echo "$verdict $label ($detail)"
  [ "$verdict" = PASS ] || failures=$((failures + 1))
}

# Calico drops packets, so a blocked connection hangs until `timeout` kills it (exit 124).
# Any other failure (refused, no route, exec error) is not proof of a policy and fails the check.
check() {
  expectation=$1 namespace=$2 pod=$3 host=$4 port=$5
  label="$expectation $pod -> $host:$port"
  if ! kubectl exec -n "$namespace" "$pod" -- getent hosts "$host" >/dev/null 2>&1; then
    report FAIL "$label" "name does not resolve"
    return
  fi
  kubectl exec -n "$namespace" "$pod" -- timeout 5 bash -c "exec 3<>/dev/tcp/$host/$port" >/dev/null 2>&1
  code=$?
  if [ "$expectation" = allow ]; then
    if [ "$code" -eq 0 ]; then report PASS "$label" "exit 0"; else report FAIL "$label" "exit $code"; fi
  else
    if [ "$code" -eq 124 ]; then report PASS "$label" "timed out"; else report FAIL "$label" "exit $code, expected 124"; fi
  fi
}

image=$(kubectl get cluster.postgresql.cnpg.io shop-db -n shop -o jsonpath='{.spec.imageName}')
if [ -z "$image" ]; then
  echo "FAIL could not read the Postgres image from cluster shop-db"
  exit 1
fi

kubectl delete namespace netpol-probe --ignore-not-found >/dev/null || exit 1
kubectl delete pod -n shop -l shop.dev/netpol-probe=true --ignore-not-found >/dev/null || exit 1
sed "s#PROBE_IMAGE#$image#" "$probes" | kubectl apply -f - || exit 1
kubectl wait --for=condition=Ready pod -l shop.dev/netpol-probe=true -n shop --timeout=90s || exit 1
kubectl wait --for=condition=Ready pod/netpol-probe-outside -n netpol-probe --timeout=90s || exit 1

check allow shop netpol-probe-api shop-db-rw 5432
check deny shop netpol-probe-web shop-db-rw 5432
check deny shop netpol-probe-other shop-db-rw 5432
check deny shop netpol-probe-web api 8080
check deny shop netpol-probe-api web 8080
check allow netpol-probe netpol-probe-outside kube-dns.kube-system 53
check deny netpol-probe netpol-probe-outside api.shop 8080
check deny netpol-probe netpol-probe-outside shop-db-rw.shop 5432
check deny netpol-probe netpol-probe-outside web.shop 8080

if [ "$failures" -ne 0 ]; then
  echo "$failures of 9 NetworkPolicy checks failed"
  exit 1
fi
echo "all 9 NetworkPolicy checks passed"
