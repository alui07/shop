#!/bin/sh
# Fails when a metric the dashboard (dashboards/shop-api.json) queries is missing from Prometheus.
# This file is the single list of those names: OTLP-to-Prometheus name translation is the likely source of a mismatch.
#
#   check-metrics.sh kind          cluster Prometheus through the API server's service proxy (run from the k8s tools container)
#   check-metrics.sh <base url>    any Prometheus, for example http://otel-lgtm:9090 on the compose network
#
# The API must already be exporting; the wait covers export steps and the first scheduled-task run, not a missing deployment.
set -u

target=${1:?usage: check-metrics.sh kind|<prometheus base url>}
timeout_seconds=${CHECK_METRICS_TIMEOUT:-150}

# Matched with job="shop-api" (OTEL_SERVICE_NAME).
api_metrics='
http_server_requests_seconds_count
hikaricp_connections_active
hikaricp_connections_idle
hikaricp_connections_pending
hikaricp_connections_max
executor_active_threads
executor_queued_tasks
shop_events_dead_letter_rows
shop_reservations_released_units_total
tasks_scheduled_execution_seconds_count
jvm_memory_used_bytes
process_cpu_usage
target_info
'
# Only the kind Prometheus scrapes the kubelet's cAdvisor endpoint.
kind_only_metrics='container_cpu_cfs_throttled_periods_total'

encode() {
  sed -e 's/{/%7B/g' -e 's/}/%7D/g' -e 's/"/%22/g' -e 's/=/%3D/g' -e 's/+/%2B/g' -e 's/!/%21/g' -e 's/(/%28/g' -e 's/)/%29/g'
}

query() {
  encoded=$(printf '%s' "$1" | encode)
  if [ "$target" = kind ]; then
    kubectl get --raw "/api/v1/namespaces/obs/services/prometheus-server:80/proxy/api/v1/query?query=$encoded" 2>/dev/null
  else
    wget -q -T 10 -O - "$target/api/v1/query?query=$encoded" 2>/dev/null
  fi
}

present() {
  response=$(query "count($1)") || return 1
  case "$response" in
    *'"status":"success"'*) ;;
    *) return 1 ;;
  esac
  case "$response" in
    *'"result":[]'*) return 1 ;;
  esac
}

selectors() {
  for name in $api_metrics; do
    case "$name" in
      target_info) echo "target_info{job=\"shop-api\"}" ;;
      *) echo "$name{job=\"shop-api\"}" ;;
    esac
  done
  # A finite bucket proves percentiles-histogram is on; with only le="+Inf" histogram_quantile returns NaN and the latency panel is empty.
  echo "http_server_requests_seconds_bucket{job=\"shop-api\",le!=\"+Inf\"}"
  if [ "$target" = kind ]; then
    for name in $kind_only_metrics; do echo "$name"; done
  fi
}

deadline=$(( $(date +%s) + timeout_seconds ))
while :; do
  missing=
  for selector in $(selectors); do
    present "$selector" || missing="$missing $selector"
  done
  if [ -z "$missing" ]; then
    echo "all dashboard metrics present ($target)"
    exit 0
  fi
  if [ "$(date +%s)" -ge "$deadline" ]; then
    echo "missing after ${timeout_seconds}s ($target):" >&2
    for selector in $missing; do echo "  $selector" >&2; done
    exit 1
  fi
  sleep 10
done
