import http from 'k6/http';
import { sleep } from 'k6';
import {
    BASE_URL,
    LOADTEST_TOKEN,
    MINT_RETRY_LIMIT,
    MINT_URL,
    SESSION_COOKIE,
    SESSION_MAX_AGE_MS,
    XSRF_COOKIE,
} from './config.js';

// Mint requests carry no `load` tag, so thresholds on {load:shop} leave them out (k6 filters by tag equality).
const MINT_TAGS = { name: 'mint', kind: 'mint' };
const MINT_STATUSES = http.expectedStatuses(201, 429);
const ME_STATUSES = http.expectedStatuses(200);

function cookieValue(res, name) {
    const cookies = res.cookies[name];
    return cookies && cookies.length ? cookies[0].value : null;
}

function mintOnce() {
    return http.post(`${MINT_URL}/api/loadtest/sessions`, null, {
        headers: { 'Shop-Loadtest-Token': LOADTEST_TOKEN },
        tags: MINT_TAGS,
        responseCallback: MINT_STATUSES,
    });
}

/**
 * Mints a customer session and fetches the XSRF cookie the way the SPA does. Cookies are sent as explicit headers:
 * k6's jar follows Go's rule and never sends Secure cookies over plain HTTP.
 */
export function mintSession() {
    let res = mintOnce();
    for (let attempt = 0; res.status === 429 && attempt < MINT_RETRY_LIMIT; attempt++) {
        sleep(Number(res.headers['Retry-After'] || 1));
        res = mintOnce();
    }
    if (res.status !== 201) {
        throw new Error(`session mint failed with status ${res.status}`);
    }
    const sessionId = cookieValue(res, SESSION_COOKIE);
    if (!sessionId) {
        throw new Error('session mint returned no session cookie');
    }
    const me = http.get(`${BASE_URL}/api/me`, {
        headers: { Cookie: `${SESSION_COOKIE}=${sessionId}` },
        tags: MINT_TAGS,
        responseCallback: ME_STATUSES,
    });
    const xsrf = cookieValue(me, XSRF_COOKIE);
    if (me.status !== 200 || !xsrf || !me.json('authenticated')) {
        throw new Error(`minted session rejected by the front door (GET /api/me status ${me.status})`);
    }
    return {
        subject: res.json('subject'),
        cookie: `${SESSION_COOKIE}=${sessionId}; ${XSRF_COOKIE}=${xsrf}`,
        xsrf,
        mintedAt: Date.now(),
    };
}

export function mintSessions(count) {
    const sessions = [];
    for (let i = 0; i < count; i++) {
        sessions.push(mintSession());
    }
    return sessions;
}

/** Re-mint a VU's own session once it is old enough; `staggerMs` (VU id based) keeps VUs from re-minting together. */
export function freshSession(session, staggerMs = 0) {
    return Date.now() - session.mintedAt > SESSION_MAX_AGE_MS - staggerMs ? mintSession() : session;
}

export function authHeaders(session, extra = {}) {
    return { Cookie: session.cookie, 'X-XSRF-TOKEN': session.xsrf, ...extra };
}
