// Full checkout with the fake payment: cart, order, payment start, fake provider completion, wait for PAID.
// Needs `checkout-setup.sql` run for RUN_ID first (products lt-<RUN_ID>-1..5 with 1 000 000 units).
import exec from 'k6/execution';
import http from 'k6/http';
import { sleep } from 'k6';
import { BASE_URL, CHECKOUT_PRODUCT_COUNT, CHECKOUT_STAGES, RUN_ID } from '../lib/config.js';
import { freshSession, mintSessions } from '../lib/session.js';
import { checkout, putCartLine, payAndAwait } from '../lib/shop.js';
import { jsonBody, rateLimitThreshold, statusIs, summaryOutputs, trendStats } from '../lib/checks.js';

const MAX_VUS = Math.max(...CHECKOUT_STAGES.map((stage) => stage.target));

export const options = {
    setupTimeout: '10m',
    scenarios: {
        checkout: { executor: 'ramping-vus', startVUs: 1, stages: CHECKOUT_STAGES, gracefulRampDown: '30s', gracefulStop: '30s' },
    },
    summaryTrendStats: trendStats,
    thresholds: {
        ...rateLimitThreshold,
        'http_req_failed{load:shop}': ['rate<0.01'],
        'http_req_duration{op:checkout}': ['p(95)<1500'],
        order_paid: ['rate>0.98'],
        time_to_paid: ['p(95)<10000'],
        'http_reqs{load:shop}': ['count>0'],
        checks: ['rate>0.99'],
    },
};

const slugs = Array.from({ length: CHECKOUT_PRODUCT_COUNT }, (_, i) => `lt-${RUN_ID}-${i + 1}`);

export function setup() {
    const probe = http.get(`${BASE_URL}/api/products/${slugs[0]}`, { tags: { name: 'setup product', load: 'setup' } });
    if (probe.status !== 200) {
        throw new Error(`product ${slugs[0]} not found (status ${probe.status}); run checkout-setup.sql for RUN_ID first`);
    }
    return { sessions: mintSessions(MAX_VUS) };
}

let session = null;

export default function (data) {
    const index = exec.vu.idInTest - 1;
    session = freshSession(session || data.sessions[index], (index % 30) * 1000);
    const wanted = 1 + Math.floor(Math.random() * 2);
    const start = Math.floor(Math.random() * slugs.length);
    let cart = null;
    for (let i = 0; i < wanted; i++) {
        const res = putCartLine(session, slugs[(start + i) % slugs.length], 1);
        if (!statusIs(res, 'add to cart', 200)) return;
        cart = jsonBody(res);
    }
    const placed = checkout(session, cart, false);
    if (!statusIs(placed, 'checkout', 201)) return;
    payAndAwait(session, jsonBody(placed).id);
    sleep(1);
}

export function handleSummary(data) {
    return summaryOutputs('checkout', data, [
        ['http_req_duration{op:checkout}', 'POST /api/checkout'],
        ['time_to_paid', 'payment start to PAID'],
    ]);
}
