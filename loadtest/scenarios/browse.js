// Anonymous catalog browsing at a fixed arrival rate. Env: RATE (iterations/s, default 30), DURATION (default 2m),
// WARMUP (default 0s; a discarded warm-up phase, tagged load:warmup so it stays out of thresholds and summary).
import { BASE_URL, BROWSE_DURATION, BROWSE_RATE, BROWSE_WARMUP } from '../lib/config.js';
import { browseVisit, discoverCatalog } from '../lib/shop.js';
import { summaryOutputs, trendStats } from '../lib/checks.js';

const arrivalRate = (duration, startTime, exec) => ({
    executor: 'constant-arrival-rate',
    rate: BROWSE_RATE,
    timeUnit: '1s',
    duration,
    startTime,
    preAllocatedVUs: Math.max(20, BROWSE_RATE),
    maxVUs: Math.max(100, BROWSE_RATE * 4),
    exec,
});

const scenarios = { browse: arrivalRate(BROWSE_DURATION, BROWSE_WARMUP, 'measured') };
if (BROWSE_WARMUP !== '0s') {
    scenarios.warmup = arrivalRate(BROWSE_WARMUP, '0s', 'warmup');
}

export const options = {
    scenarios,
    summaryTrendStats: trendStats,
    thresholds: {
        'http_req_failed{load:shop}': ['rate<0.01'],
        'http_req_duration{load:shop}': ['p(95)<500'],
        'http_reqs{load:shop}': ['count>0'],
        // Always passes; makes k6 compute the sub-metric so the summary row appears (and excludes warm-up).
        'http_req_duration{op:GET /api/products,load:shop}': ['max>=0'],
        checks: ['rate>0.99'],
    },
};

export function setup() {
    return discoverCatalog();
}

export function measured(catalog) {
    browseVisit(catalog, 'shop');
}

export function warmup(catalog) {
    browseVisit(catalog, 'warmup');
}

export function handleSummary(data) {
    return summaryOutputs('browse', data, [['http_req_duration{op:GET /api/products,load:shop}', 'GET /api/products']]);
}
