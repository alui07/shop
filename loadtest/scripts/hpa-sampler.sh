#!/bin/sh
# Usage: hpa-sampler.sh /reports/hpa.csv  Appends one row every 5 s until killed.
set -u
csv=${1:?usage: hpa-sampler.sh <csv file>}
[ -s "$csv" ] || echo "time,epoch,current_replicas,desired_replicas,cpu_percent,ready_api_pods" >"$csv"
while true; do
    epoch=$(date -u +%s)
    time=$(date -u -d "@$epoch" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u +%Y-%m-%dT%H:%M:%SZ)
    hpa=$(kubectl -n shop get hpa api -o jsonpath='{.status.currentReplicas},{.status.desiredReplicas},{.status.currentMetrics[0].resource.current.averageUtilization}' 2>/dev/null || true)
    IFS=, read -r current desired cpu <<EOT
$hpa
EOT
    ready=$(kubectl -n shop get deployment api -o jsonpath='{.status.readyReplicas}' 2>/dev/null || true)
    echo "$time,$epoch,${current:-},${desired:-},${cpu:-},${ready:-0}" >>"$csv"
    sleep 5
done
