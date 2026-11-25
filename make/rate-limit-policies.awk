# Prints every line of every BackendTrafficPolicy and ClientTrafficPolicy document of a rendered overlay, so a guarded overlay can be compared with base in full (name, selectors, methods, units, request buffer, response overrides).
/^---/ { keep = 0 }
/^kind: / { keep = ($2 == "BackendTrafficPolicy" || $2 == "ClientTrafficPolicy") }
keep { print }
