#!/bin/sh
# HPA evidence for cluster-e2e. Run from the repo root in the k8s tools container.
#   hpa-report.sh sample <csv>     append the HPA's replica counts every 5 s until the container is removed
#   hpa-report.sh summary <csv>    print the observed replica range, the rescale events and the current HPA state
set -u

mode=${1:?usage: hpa-report.sh sample|summary <csv>}
csv=${2:?usage: hpa-report.sh sample|summary <csv>}

case "$mode" in
  sample)
    echo "time,current,desired,ready,cpu_percent" > "$csv"
    while :; do
      hpa=$(kubectl get hpa api -n shop -o jsonpath='{.status.currentReplicas},{.status.desiredReplicas}' 2>/dev/null)
      ready=$(kubectl get deploy api -n shop -o jsonpath='{.status.readyReplicas}' 2>/dev/null)
      cpu=$(kubectl get hpa api -n shop -o jsonpath='{.status.currentMetrics[0].resource.current.averageUtilization}' 2>/dev/null)
      echo "$(date -u +%T),${hpa:-,},${ready:-0},${cpu:-unknown}" >> "$csv"
      sleep 5
    done
    ;;
  summary)
    echo "HPA api during cluster-e2e (samples every 5 s, $(( $(wc -l < "$csv") - 1 )) rows)"
    awk -F, 'NR > 1 { if ($2 + 0 > max) max = $2 + 0; if (min == "" || $2 + 0 < min) min = $2 + 0; if ($4 + 0 > ready) ready = $4 + 0 }
      END { printf "current replicas: min %s, max %s; most ready pods %s\n", min, max, ready }' "$csv"
    echo "rescale events:"
    kubectl get events -n shop --field-selector involvedObject.name=api,reason=SuccessfulRescale --no-headers 2>/dev/null || true
    kubectl get hpa api -n shop
    kubectl top pods -n shop -l app.kubernetes.io/name=api 2>/dev/null || true
    ;;
  *)
    echo "unknown mode: $mode" >&2
    exit 2
    ;;
esac
