#!/bin/sh
# Usage: hpa-gate.sh /reports  C1 gate over hpa.csv, t0 and t1: all three parts must hold or the run fails.
set -u
dir=${1:?usage: hpa-gate.sh <report dir>}
csv=$dir/hpa.csv
out=$dir/hpa-gate.txt
t0=$(cat "$dir/t0")
t1=$(cat "$dir/t1")
min=$(kubectl -n shop get hpa api -o jsonpath='{.spec.minReplicas}')
t0_iso=$(date -u -d "@$t0" +%Y-%m-%dT%H:%M:%SZ)

desired_peak=$(awk -F, -v t0="$t0" 'NR > 1 && $2 ~ /^[0-9]+$/ && $2 > t0 && $4 ~ /^[0-9]+$/ && $4 > m { if ($4 > p) p = $4 } END { print p + 0 }' m="$min" "$csv")
ready_peak=$(awk -F, -v t0="$t0" -v t1="$t1" 'NR > 1 && $2 ~ /^[0-9]+$/ && $2 >= t0 && $2 <= t1 && $6 ~ /^[0-9]+$/ { if ($6 > p) p = $6 } END { print p + 0 }' "$csv")
events=$(kubectl -n shop get events --field-selector reason=SuccessfulRescale,involvedObject.kind=HorizontalPodAutoscaler,involvedObject.name=api \
    -o jsonpath='{range .items[*]}{.lastTimestamp}{"|"}{.eventTime}{"|"}{.metadata.creationTimestamp}{"|"}{.message}{"\n"}{end}' 2>/dev/null)
# The HPA is settled at min before t0 (settle.sh), so a rescale event after t0 whose new size exceeds min is a scale-up.
rescales_after_t0=$(printf '%s\n' "$events" | awk -F'|' -v t0="$t0_iso" -v min="$min" 'NF >= 4 { ts = $1; if (ts == "") ts = $2; if (ts == "") ts = $3; if (ts >= t0 && match($4, /New size: [0-9]+/)) { size = substr($4, RSTART + 10, RLENGTH - 10) + 0; if (size > min) n++ } } END { print n + 0 }')

fail=0
verdict() {
    if [ "$1" = yes ]; then echo "PASS $2"; else echo "FAIL $2"; fail=1; fi
}
{
    echo "HPA minReplicas=$min t0=$t0 ($t0_iso) t1=$t1"
    if [ "$desired_peak" -gt "$min" ]; then verdict yes "desired replicas rose above min after t0 (peak $desired_peak)"; else verdict no "desired replicas rose above min after t0 (peak $desired_peak)"; fi
    if [ "$rescales_after_t0" -gt 0 ]; then verdict yes "SuccessfulRescale scale-up event for the api HPA after t0 (count $rescales_after_t0)"; else verdict no "SuccessfulRescale scale-up event for the api HPA after t0 (count $rescales_after_t0)"; fi
    if [ "$ready_peak" -gt "$min" ]; then verdict yes "ready API pods exceeded min within the flash-sale window (peak $ready_peak)"; else verdict no "ready API pods exceeded min within the flash-sale window (peak $ready_peak)"; fi
    echo
    echo "SuccessfulRescale events (lastTimestamp|eventTime|creationTimestamp|message):"
    printf '%s\n' "$events"
    echo
    echo "kubectl top pods -n shop:"
    kubectl top pods -n shop 2>&1
} >"$out"
cat "$out"
exit "$fail"
