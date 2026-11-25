# Prints "<policy name> <requests>" for every Envoy rate-limit rule of every BackendTrafficPolicy in a rendered overlay, in document order.
/^---/ { kind = ""; name = "" }
/^kind: / { kind = $2 }
/^  name: / && name == "" { name = $2 }
kind == "BackendTrafficPolicy" && /^ +requests: [0-9]+$/ { print name, $2 }
