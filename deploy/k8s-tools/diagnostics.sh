#!/bin/sh
# Collects cluster state for failed runs. Never reads Secrets. Runs in the k8s-tools container with /reports mounted.
out=/reports/diagnostics
mkdir -p "$out/logs"

kubectl get nodes,all,gateway,httproute,gatewayclass,envoyproxy,cluster.postgresql.cnpg.io,networkpolicy,pvc -A -o wide > "$out/resources.txt" 2>&1
kubectl get events -A --sort-by=.lastTimestamp > "$out/events.txt" 2>&1
kubectl describe pods -n shop > "$out/describe-shop-pods.txt" 2>&1

for namespace in shop envoy-gateway-system cnpg-system; do
	for pod in $(kubectl get pods -n "$namespace" -o name 2>/dev/null); do
		kubectl logs -n "$namespace" "$pod" --all-containers --prefix --tail=2000 > "$out/logs/$namespace-${pod#pod/}.log" 2>&1
	done
done
for pod in $(kubectl get pods -n kube-system -l k8s-app=calico-node -o name 2>/dev/null); do
	kubectl logs -n kube-system "$pod" --all-containers --prefix --tail=2000 > "$out/logs/kube-system-${pod#pod/}.log" 2>&1
done
exit 0
