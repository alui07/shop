# Reads a rendered overlay; fails unless the HTTPRoute named shop has a rule that:
#  - matches only the path prefix /api/loadtest (type PathPrefix, one path, no method, headers or queryParams),
#  - applies an ExtensionRef filter named not-found (kind HTTPRouteFilter), and
#  - has no backendRefs.
# The route must be in namespace shop, and only one HTTPRoute may be named shop.
# Rules of any other route, a backendRef named not-found, or a different match type do not count.
function close_rule() {
	if (isRoute && routeName == "shop" && routeNs == "shop" && paths == 1 && pathType == "PathPrefix" && pathValue == "/api/loadtest" \
		&& !otherMatch && hasExtensionRef && hasKind && hasNotFound && !hasBackend) ok = 1
	paths = 0; pathType = ""; pathValue = ""; otherMatch = 0; hasExtensionRef = 0; hasKind = 0; hasNotFound = 0; hasBackend = 0
}
function close_doc() {
	close_rule()
	if (isRoute && routeName == "shop") shopRoutes++
	isRoute = 0; routeName = ""; routeNs = ""
}
/^---/ { close_doc() }
/^kind: HTTPRoute$/ { isRoute = 1 }
/^  name: / { routeName = substr($0, 9) }
/^  namespace: / { routeNs = substr($0, 14) }
/^  - / { close_rule() }
/^  [a-zA-Z]/ && !/^  name: / && !/^  - / { close_rule() }
/^ +(- )?backendRefs:/ { hasBackend = 1 }
/^ +(- )?extensionRef:/ { hasExtensionRef = 1 }
/^ +kind: HTTPRouteFilter$/ { hasKind = 1 }
/^ +name: not-found$/ { hasNotFound = 1 }
/^ +- path:|^ +path:$/ { paths++ }
/^ +type: / && pathType == "" && paths == 1 { t = $0; sub(/^ +type: /, "", t); if (t != "ExtensionRef" && t != "RequestHeaderModifier") pathType = t }
/^ +value: \// && pathValue == "" { v = $0; sub(/^ +value: /, "", v); pathValue = v }
/^ +(- )?(method|headers|queryParams):/ { otherMatch = 1 }
END {
	close_doc()
	if (shopRoutes > 1) { print "more than one HTTPRoute named shop" > "/dev/stderr"; ok = 0 }
	else if (!ok) print "no rule in HTTPRoute shop (namespace shop) sends the PathPrefix /api/loadtest (no method) to the not-found ExtensionRef filter" > "/dev/stderr"
	exit !ok
}
