#!/bin/sh
# Proves the api PodDisruptionBudget (maxUnavailable 1) through the Eviction API: the first eviction is accepted, the second, issued at once for
# another pod, is refused with HTTP 429. Also checks the metrics API serves the shop pods. Run from the repo root in the k8s tools container.
set -u

namespace=shop
selector=app.kubernetes.io/name=api,app.kubernetes.io/part-of=shop

fail() {
  echo "FAIL $1" >&2
  exit 1
}

# Running pods that are not being deleted, one name per line.
active_pods() {
  kubectl get pods -n "$namespace" -l "$selector" \
    -o jsonpath='{range .items[?(@.status.phase=="Running")]}{.metadata.name}{" "}{.metadata.deletionTimestamp}{"\n"}{end}' | awk 'NF == 1 { print $1 }'
}

evict() {
  printf '{"apiVersion":"policy/v1","kind":"Eviction","metadata":{"name":"%s","namespace":"%s"}}' "$1" "$namespace" \
    | kubectl create --raw "/api/v1/namespaces/$namespace/pods/$1/eviction" -f - 2>&1
}

wait_for_ready_pods() {
  min=$(kubectl get hpa api -n "$namespace" -o jsonpath='{.spec.minReplicas}')
  deadline=$(( $(date +%s) + 300 ))
  while :; do
    ready=$(kubectl get deploy api -n "$namespace" -o jsonpath='{.status.readyReplicas}')
    total=$(active_pods | wc -l)
    if [ "${ready:-0}" -ge "$min" ] && [ "$total" -ge "$min" ]; then return 0; fi
    [ "$(date +%s)" -lt "$deadline" ] || return 1
    sleep 3
  done
}

# After an HPA scale-up a new pod can still be starting, and the PDB status lags a pod turning ready; the first eviction needs a budget of at least 1.
wait_for_disruption_budget() {
  deadline=$(( $(date +%s) + 300 ))
  while :; do
    allowed=$(kubectl get pdb api -n "$namespace" -o jsonpath='{.status.disruptionsAllowed}')
    if [ "${allowed:-0}" -ge 1 ]; then return 0; fi
    if [ "$(date +%s)" -ge "$deadline" ]; then
      echo "pdb/api: currentHealthy=$(kubectl get pdb api -n "$namespace" -o jsonpath='{.status.currentHealthy}') expectedPods=$(kubectl get pdb api -n "$namespace" -o jsonpath='{.status.expectedPods}')" >&2
      return 1
    fi
    sleep 3
  done
}

wait_for_ready_pods || fail "fewer than the HPA minimum of api pods are ready before the test"
wait_for_disruption_budget || fail "pdb/api allowed no disruption within 300 s before the test"
first=$(active_pods | sed -n 1p)
second=$(active_pods | sed -n 2p)
[ -n "$first" ] && [ -n "$second" ] || fail "need two running api pods"

result=$(evict "$first") || fail "first eviction of $first was refused: $result"
echo "PASS first eviction accepted ($first)"

result=$(evict "$second") && fail "second eviction of $second was accepted; the PDB should refuse it"
case "$result" in
  *TooManyRequests*|*"disruption budget"*|*429*) echo "PASS second eviction refused with 429 ($second)" ;;
  *) fail "second eviction failed for another reason: $result" ;;
esac

wait_for_ready_pods || fail "the api did not recover to the HPA minimum within 300 s after the eviction"
echo "PASS api recovered after the eviction"

deadline=$(( $(date +%s) + 120 ))
until [ -n "$(kubectl top pods -n "$namespace" --no-headers 2>/dev/null)" ]; do
  [ "$(date +%s)" -lt "$deadline" ] || fail "the metrics API returned no shop pods"
  sleep 5
done
echo "PASS metrics API serves the shop pods"
