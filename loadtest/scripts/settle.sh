#!/bin/sh
# Usage: settle.sh /reports/hpa.csv [--warn-only]
# C1 settle: HPA desired = current = minReplicas and CPU under the target for 60 s; gives up after 6 min.
set -u
csv=${1:?usage: settle.sh <csv file> [--warn-only]}
warn_only=no
[ "${2:-}" = "--warn-only" ] && warn_only=yes
interval=5
quiet_needed=60
timeout=360
quiet=0
waited=0
while [ "$waited" -lt "$timeout" ]; do
    hpa=$(kubectl -n shop get hpa api -o jsonpath='{.spec.minReplicas},{.status.currentReplicas},{.status.desiredReplicas},{.status.currentMetrics[0].resource.current.averageUtilization},{.spec.metrics[0].resource.target.averageUtilization}' 2>/dev/null || true)
    IFS=, read -r min current desired cpu target <<EOT
$hpa
EOT
    target=${target:-60}
    if [ -n "$min" ] && [ "$current" = "$min" ] && [ "$desired" = "$min" ] && [ -n "$cpu" ] && [ "$cpu" -lt "$target" ]; then
        quiet=$((quiet + interval))
    else
        quiet=0
    fi
    if [ "$quiet" -ge "$quiet_needed" ]; then
        epoch=$(date -u +%s)
        [ -s "$csv" ] || echo "time,epoch,current_replicas,desired_replicas,cpu_percent,ready_api_pods" >"$csv"
        echo "$(date -u +%Y-%m-%dT%H:%M:%SZ),$epoch,settled,settled,settled,settled" >>"$csv"
        echo "settled after ${waited}s"
        exit 0
    fi
    sleep "$interval"
    waited=$((waited + interval))
done
echo "HPA did not settle within ${timeout}s (min=${min:-?} current=${current:-?} desired=${desired:-?} cpu=${cpu:-?})" >&2
[ "$warn_only" = yes ] && exit 0
exit 1
