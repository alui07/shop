#!/bin/sh
# Waits until the api Deployment has at least the HPA's minReplicas ready pods. A fresh Deployment starts at 1 and the HPA raises it to its
# minimum; that needs no metrics, so the ~45 s `cpu: <unknown>` window after a pod starts does not delay it.
# Run from the repo root in the k8s tools container.
set -u

min=$(kubectl get hpa api -n shop -o jsonpath='{.spec.minReplicas}') || exit 1
deadline=$(( $(date +%s) + 300 ))
while :; do
  ready=$(kubectl get deploy api -n shop -o jsonpath='{.status.readyReplicas}')
  if [ "${ready:-0}" -ge "$min" ]; then
    echo "api: ${ready} ready pods (HPA minimum ${min})"
    exit 0
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "api: ${ready:-0} ready pods after 300 s, the HPA minimum is ${min}" >&2
    exit 1
  fi
  sleep 3
done
