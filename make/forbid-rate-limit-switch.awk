# Fails when a rendered overlay carries an API rate-limit switch in any spelling (SHOP_RATE_LIMIT_*, SHOP_RATELIMIT_*, shop.rate-limit, inside SPRING_APPLICATION_JSON or not).
tolower($0) ~ /shop[._-]?rate[._-]?limit/ { print "API rate-limit setting in rendered output: " $0 > "/dev/stderr"; bad = 1 }
END { exit bad }
