// Flash sale for 100 units of product flash-<RUN_ID> (flash-setup.sql). 150 authenticated VUs try at once, then the
// hold phase keeps the API busy for the HPA: anonymous browse at a constant arrival rate (FLASH_HOLD_BROWSE_RATE,
// to be calibrated) plus a few authenticated checkout VUs that mostly meet 409 out-of-stock.
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';
import { sleep } from 'k6';
import {
    FLASH_BURST_DURATION,
    FLASH_BURST_VUS,
    FLASH_HOLD_BROWSE_RATE,
    FLASH_HOLD_CHECKOUT_VUS,
    FLASH_HOLD_DURATION,
    FLASH_HOLD_START,
    FLASH_UNITS,
    RUN_ID,
} from '../lib/config.js';
import { freshSession, mintSessions } from '../lib/session.js';
import { browseVisit, checkout, classifyConflict, discoverCatalog, payAndAwait, putCartLine } from '../lib/shop.js';
import { jsonBody, rateLimitThreshold, statusIs, summaryOutputs, trendStats } from '../lib/checks.js';

const created = new Counter('flash_orders_created');
const paid = new Counter('flash_orders_paid');
const flashSlug = `flash-${RUN_ID}`;
const minutes = (duration) => Number(duration.replace(/m$/, ''));
// Test-run time at which the hold ends: it starts FLASH_HOLD_START in, after the burst has sold out.
const HOLD_END_MS = (minutes(FLASH_HOLD_START) + minutes(FLASH_HOLD_DURATION)) * 60 * 1000;

export const options = {
    setupTimeout: '10m',
    scenarios: {
        burst: { executor: 'per-vu-iterations', vus: FLASH_BURST_VUS, iterations: 1, maxDuration: FLASH_BURST_DURATION, exec: 'burst' },
        hold_checkout: {
            executor: 'per-vu-iterations',
            startTime: FLASH_HOLD_START,
            vus: FLASH_HOLD_CHECKOUT_VUS,
            iterations: 1,
            maxDuration: '10m',
            exec: 'holdCheckout',
        },
        hold_browse: {
            executor: 'constant-arrival-rate',
            startTime: FLASH_HOLD_START,
            rate: FLASH_HOLD_BROWSE_RATE,
            timeUnit: '1s',
            duration: FLASH_HOLD_DURATION,
            preAllocatedVUs: 50,
            maxVUs: 400,
            exec: 'holdBrowse',
        },
    },
    summaryTrendStats: trendStats,
    thresholds: {
        ...rateLimitThreshold,
        flash_orders_created: [`count<=${FLASH_UNITS}`],
        flash_orders_paid: [`count<=${FLASH_UNITS}`],
        'http_req_failed{load:shop}': ['rate<0.01'],
        'http_reqs{load:shop}': ['count>0'],
        // Always pass; they make k6 compute these sub-metrics so the summary can report latency.
        'http_req_duration{load:shop}': ['max>=0'],
        'http_req_duration{op:checkout}': ['max>=0'],
    },
};

export function setup() {
    return {
        burst: mintSessions(FLASH_BURST_VUS),
        hold: mintSessions(FLASH_HOLD_CHECKOUT_VUS),
        catalog: discoverCatalog(),
    };
}

let session = null;

function attemptPurchase() {
    const line = putCartLine(session, flashSlug, 1);
    if (!statusIs(line, 'add flash product', 200)) return;
    const res = checkout(session, jsonBody(line), true);
    if (!statusIs(res, 'flash checkout', 201, 409)) return;
    if (res.status === 409) {
        classifyConflict(res);
        return;
    }
    created.add(1);
    if (payAndAwait(session, jsonBody(res).id)) paid.add(1);
}

export function burst(data) {
    session = data.burst[exec.scenario.iterationInInstance];
    attemptPurchase();
}

// One long iteration per VU keeps the session index stable; sessions are refreshed when they get old.
export function holdCheckout(data) {
    const index = exec.scenario.iterationInInstance;
    session = data.hold[index];
    while (exec.instance.currentTestRunDuration < HOLD_END_MS) {
        session = freshSession(session, index * 1000);
        attemptPurchase();
        sleep(1);
    }
}

export function holdBrowse(data) {
    browseVisit(data.catalog, 'shop');
}

export function handleSummary(data) {
    return summaryOutputs('flash-sale', data, [['http_req_duration{op:checkout}', 'POST /api/checkout']]);
}
