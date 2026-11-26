#!/bin/sh
# After limits-test.js: the Envoy access log written since <start time> must hold the k6 requests, none with a spoofed 203.0.113.x
# X-Forwarded-For (Envoy replaces the header with the real peer) and none with the forged x-envoy-original-path marker as its path.
# Usage: limits-access-log-test.sh <RFC 3339 start time> <limits-test.js output file>. Run from the repo root in the k8s tools container.
set -u

# limits-test.js sends the forged-header request with this user agent, so its log line is told apart from any other GET /api/status.
forged_request_pattern='"GET /api/status HTTP/1.1".*"shop-limits-forged-path-probe"'

since=${1:?usage: limits-access-log-test.sh <start time> <k6 output file>}
k6_output=${2:?usage: limits-access-log-test.sh <start time> <k6 output file>}

fail() {
  echo "FAIL $1" >&2
  exit 1
}

marker=$(sed -n 's/.*LIMITS_FORGED_PATH_MARKER=\([^ ]*\).*/\1/p' "$k6_output" | tail -n 1)
[ -n "$marker" ] || fail "no LIMITS_FORGED_PATH_MARKER line in $k6_output (did limits-test.js finish?)"

# Envoy flushes its access log about once a second, so the last request can be missing right after k6 exits.
attempt=0
while :; do
  log=$(kubectl logs -n envoy-gateway-system -l gateway.envoyproxy.io/owning-gateway-name=shop -c envoy --since-time="$since" --max-log-requests=10) \
    || fail "could not read the Envoy access log"
  printf '%s\n' "$log" | grep -Eq "$forged_request_pattern" && break
  attempt=$((attempt + 1))
  [ "$attempt" -lt 10 ] || break
  sleep 1
done

printf '%s\n' "$log" | grep -q 'Grafana k6' || fail "the Envoy access log holds no k6 request since $since"
if printf '%s\n' "$log" | grep -F '203.0.113.'; then fail "a spoofed X-Forwarded-For address reached the access log"; fi
if printf '%s\n' "$log" | grep -F "$marker"; then fail "the forged x-envoy-original-path marker $marker was logged as a path"; fi
printf '%s\n' "$log" | grep -Eq "$forged_request_pattern" || fail "the forged-path request was not logged under its real path /api/status"
echo "ok: no spoofed address and no forged path in the Envoy access log"
