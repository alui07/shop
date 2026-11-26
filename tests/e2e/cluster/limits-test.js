/* global __ENV */
// Proves Envoy's per-client-IP limits on the kind cluster. Run by make cluster-e2e in the k6 image, in its own container on the
// kind network, so its address has its own buckets. The XFF values are spoofed to show that they buy no extra bucket.
//
// Contract with make cluster-e2e:
//   env SHOP_URL   front door NodePort            (default http://shop-control-plane:30080)
//   env IDP_URL    Keycloak NodePort              (default http://shop-control-plane:30089)
//   env SHOP_HOST  Host header for the front door (default localhost:8088)
//   env IDP_HOST   Host header for Keycloak       (default localhost:8089)
//   env FORGED_PATH_MARKER  path sent in a forged x-envoy-original-path (default /forged-original-path-marker)
//   run:  k6 run /e2e/cluster/limits-test.js     (the script mounted read-only; no output file is written)
// Exit code is non-zero when any check fails. The last stdout lines report the spoofed XFF range and the marker, for the
// access-log grep: spoofed addresses must not appear in the log, and the marker must not appear as a request path.
import http from 'k6/http';
import { check, sleep } from 'k6';

const shopUrl = __ENV.SHOP_URL || 'http://shop-control-plane:30080';
const idpUrl = __ENV.IDP_URL || 'http://shop-control-plane:30089';
const shopHost = __ENV.SHOP_HOST || 'localhost:8088';
const idpHost = __ENV.IDP_HOST || 'localhost:8089';
const forgedPathUserAgent = 'shop-limits-forged-path-probe';
const forgedPathMarker = __ENV.FORGED_PATH_MARKER || '/forged-original-path-marker';

// Per-case ceilings are twice the rule's limit, so a 429 must show up well before the loop ends.
const loginStartPerMinute = 125;
const checkoutPerMinute = 30;
const loginActionsPerMinute = 125;
// Playwright's checkout peak is about 10 a minute against 30, and Docker may hand this container an address Playwright
// used in the last minute, so checkout keeps slack: its first 10 must pass, the others' first 20.
const mustPassFirst = 20;
const checkoutMustPassFirst = 10;
// Stays well under the 200/s per-IP flood rule, so a 429 can only come from the rule under test.
const paceSeconds = 0.025;
const rateLimitedType = 'tag:shop.dev,2026:rate-limited';
const bodyTooLargeType = 'tag:shop.dev,2026:body-too-large';

export const options = {
  vus: 1,
  iterations: 1,
  maxRedirects: 0,
  thresholds: { checks: ['rate==1'] },
};

function spoofedAddress(index) {
  return `203.0.113.${(index % 254) + 1}`;
}

function retryAfterOf(response) {
  const name = Object.keys(response.headers).find((key) => key.toLowerCase() === 'retry-after');
  return name ? response.headers[name] : undefined;
}

function problemTypeOf(response) {
  try {
    return response.json('type');
  } catch {
    return undefined;
  }
}

// Sends up to twice the limit and stops at the first 429 after the grace window, checks every 429 it saw. A HEAD 429 has
// no body, so the loop goes on past it until a request with a body is limited; body checks never run on a HEAD response.
function exhaust(name, send, { limit, problemBody, passFirst = mustPassFirst }) {
  const maxRequests = limit * 2;
  const statuses = [];
  const limited = [];
  for (let i = 0; i < maxRequests; i += 1) {
    const response = send(i);
    statuses.push(response.status);
    if (response.status === 429) {
      limited.push(response);
      if (i >= passFirst && response.request.method !== 'HEAD') break;
    }
    sleep(paceSeconds);
  }
  check(statuses, {
    [`${name}: none of the first ${passFirst} is 429`]: (all) =>
      !all.slice(0, passFirst).includes(429),
    [`${name}: a 429 appears within ${maxRequests}`]: (all) => all.includes(429),
  });
  for (const response of limited) {
    check(response, {
      [`${name}: 429 has an integer Retry-After`]: (r) => /^\d+$/.test(retryAfterOf(r) ?? ''),
    });
  }
  if (problemBody) {
    const withBody = limited.filter((response) => response.request.method !== 'HEAD');
    check(withBody, {
      [`${name}: a 429 with a body appears`]: (all) => all.length > 0,
      [`${name}: every 429 with a body is the rate-limited problem`]: (all) =>
        all.every(
          (r) =>
            (r.headers['Content-Type'] ?? '').includes('application/problem+json') &&
            problemTypeOf(r) === rateLimitedType,
        ),
    });
  }
}

function shopRequest(method, path, forwardedFor, body) {
  return http.request(method, `${shopUrl}${path}`, body, {
    headers: { Host: shopHost, 'X-Forwarded-For': forwardedFor },
    redirects: 0,
  });
}

function loginStart() {
  // HEAD spends the whole budget first, so a GET 429 inside the request cap can only happen when HEAD counted: if it did
  // not, 'limit' GETs would still pass and the cap would end the loop. The spoofed address must not move a request to a new bucket.
  exhaust(
    'login start',
    (i) =>
      shopRequest(
        i < loginStartPerMinute ? 'HEAD' : 'GET',
        '/oauth2/authorization/keycloak',
        spoofedAddress(i),
      ),
    { limit: loginStartPerMinute, problemBody: true },
  );
}

function checkoutWithQuery() {
  // Anonymous is fine: Envoy limits before the API answers 401 or 403. The query string must not escape the rule.
  exhaust('checkout', (i) => shopRequest('POST', '/api/checkout?x=1', spoofedAddress(i + 100)), {
    limit: checkoutPerMinute,
    problemBody: true,
    passFirst: checkoutMustPassFirst,
  });
}

function keycloakLoginAction() {
  // Keycloak answers these with an expired-session page, so no real account is touched. Its 429 is plain text.
  exhaust(
    'login-actions',
    (i) =>
      http.post(
        `${idpUrl}/realms/shop/login-actions/authenticate`,
        { username: 'nobody@example.test', password: 'not-a-password' },
        { headers: { Host: idpHost, 'X-Forwarded-For': spoofedAddress(i + 200) }, redirects: 0 },
      ),
    { limit: loginActionsPerMinute, problemBody: false },
  );
}

function oversizedBody() {
  // POST / reaches the web backend, so a 413 here can only come from Envoy's request buffer limit.
  const response = shopRequest('POST', '/', spoofedAddress(1), 'a'.repeat(70 * 1024));
  check(response, {
    'oversized body: 413': (r) => r.status === 413,
    'oversized body: problem body': (r) =>
      (r.headers['Content-Type'] ?? '').includes('application/problem+json') &&
      problemTypeOf(r) === bodyTooLargeType,
  });
}

function forgedOriginalPath() {
  const response = http.get(`${shopUrl}/api/status`, {
    headers: {
      Host: shopHost,
      'X-Forwarded-For': spoofedAddress(2),
      'x-envoy-original-path': forgedPathMarker,
      'User-Agent': forgedPathUserAgent,
    },
    redirects: 0,
  });
  check(response, {
    'forged x-envoy-original-path: request still served': (r) => r.status === 200,
  });
}

export default function () {
  loginStart();
  checkoutWithQuery();
  keycloakLoginAction();
  oversizedBody();
  forgedOriginalPath();
  console.log(`LIMITS_SPOOFED_RANGE=203.0.113.0/24 LIMITS_FORGED_PATH_MARKER=${forgedPathMarker}`);
}
