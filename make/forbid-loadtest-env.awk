# Reads the whole rendered output of one overlay that must not enable load-test session minting. Fails on:
#  - any case-insensitive "loadtest", except the two exact base Gateway lines (the /api/loadtest not-found rule and the Shop-Loadtest-Token header strip); the path value line may appear only once, so a second rule on /api/loadtest fails;
#  - any Kubernetes dependent-variable expansion other than $(POD_NAME), which could assemble the word from parts;
#  - a case-insensitive spring.application.json key, property or variable in any spelling (. _ -), which can carry the profile outside the scanned word;
#  - any \u escape, which can hide a word from the scan;
#  - ${...} placeholders in a value: line, which Spring resolves from other properties. Only value: lines are checked: the real renders carry legitimate ${...} elsewhere (Keycloak args and realm JSON), so container args and ConfigMap data are not;
#  - type: RegularExpression path matches, which could build the /api/loadtest path from a pattern (the real renders have none);
#  - binaryData and envFrom, which carry values the render shows only encoded or by reference. No overlay uses envFrom; add an allowance here before one does.
# The shop-loadtest Secret is created by make deploy from the secrets file, not rendered by kustomize, so it needs no allowance.
{
	line = $0
	sub(/^[ \t]+/, "", line)
	sub(/[ \t]+$/, "", line)
}
line == "value: /api/loadtest" && ++pathValues > 1 { print "second /api/loadtest path value in rendered output: " $0 > "/dev/stderr"; bad = 1 }
tolower($0) ~ /loadtest/ && line != "value: /api/loadtest" && line != "- Shop-Loadtest-Token" { print "loadtest in rendered output: " $0 > "/dev/stderr"; bad = 1 }
{
	rest = $0
	while (match(rest, /\$\([^)]*\)/)) {
		ref = substr(rest, RSTART, RLENGTH)
		if (ref != "$(POD_NAME)") { print "dependent-variable expansion: " $0 > "/dev/stderr"; bad = 1 }
		rest = substr(rest, RSTART + RLENGTH)
	}
}
tolower($0) ~ /spring[._-]application[._-]json/ { print "spring.application.json in rendered output: " $0 > "/dev/stderr"; bad = 1 }
/\\u/ { print "\\u escape in rendered output: " $0 > "/dev/stderr"; bad = 1 }
/^[ \t]*(- )?value: .*\$\{/ { print "placeholder in a rendered value: " $0 > "/dev/stderr"; bad = 1 }
/^[ \t]*binaryData:/ { print "binaryData in rendered output: " $0 > "/dev/stderr"; bad = 1 }
/^[ \t]*(- )?envFrom:/ { print "envFrom in rendered output: " $0 > "/dev/stderr"; bad = 1 }
/^[ \t]*(- )?type: RegularExpression[ \t]*$/ { print "RegularExpression match in rendered output: " $0 > "/dev/stderr"; bad = 1 }
END { exit bad }
