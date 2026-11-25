// C6: with the loadtest profile on, the front door must never mint a session. Every spelling of the mint path, with
// the valid token, must avoid 2xx and any __Host-SESSION cookie, and the spellings Envoy catches must answer exactly 404. The k6 image has no curl, so this is a k6 script.
import http from 'k6/http';
import { Counter } from 'k6/metrics';
import { BASE_URL, LOADTEST_TOKEN, REPORT_DIR, SESSION_COOKIE } from '../lib/config.js';

const violations = new Counter('probe_violations');

const PATHS = [
    '/api/loadtest/sessions',
    '/api/loadtest/sessions/',
    '/api/%6Coadtest/sessions',
    '/api/loadtest%2Fsessions',
    '/api/loadtest/sessions%2F',
    '/api//loadtest/sessions',
    '//api/loadtest/sessions',
    '/api/./loadtest/sessions',
    '/api/loadtest/./sessions',
    '/api/LOADTEST/SESSIONS',
    '/api/LoadTest/Sessions',
    '/API/loadtest/sessions',
    '/api/loadtest/sessions;jsessionid=x',
    '/api/loadtest;x=1/sessions',
    '/api/loadtest',
    '/api/loadtest/',
];
// Spellings that Envoy normalises into the /api/loadtest prefix. Its not-found rule must answer exactly 404 for every method, so removing
// the rule (leaving the API to answer 401 or 400 without the stripped token) fails the probe. Recorded in build/reports/loadtest/lr-browse/probe.txt.
const ENVOY_NOT_FOUND = new Set([
    '/api/loadtest/sessions',
    '/api/loadtest/sessions/',
    '/api/%6Coadtest/sessions',
    '/api//loadtest/sessions',
    '//api/loadtest/sessions',
    '/api/./loadtest/sessions',
    '/api/loadtest/./sessions',
    '/api/loadtest/sessions;jsessionid=x',
    '/api/loadtest',
    '/api/loadtest/',
]);
const METHODS = ['POST', 'GET', 'HEAD', 'PUT', 'DELETE'];

export const options = {
    iterations: 1,
    thresholds: { probe_violations: ['count==0'] },
};

export function setup() {
    if (!LOADTEST_TOKEN) {
        throw new Error('SHOP_LOADTEST_TOKEN is not set: the probe needs the valid token to be meaningful');
    }
    const lines = [];
    for (const path of PATHS) {
        for (const method of METHODS) {
            const res = http.request(method, `${BASE_URL}${path}`, null, {
                headers: { 'Shop-Loadtest-Token': LOADTEST_TOKEN },
                tags: { name: 'probe' },
                redirects: 0,
                responseCallback: http.expectedStatuses({ min: 100, max: 599 }),
            });
            const setsSession = String(res.headers['Set-Cookie'] || '').includes(SESSION_COOKIE);
            // The web server's SPA fallback answers 200 text/html on unknown paths such as /API/...; a mint would be JSON.
            const servedSpaFallback = String(res.headers['Content-Type'] || '').startsWith('text/html');
            const success = res.status >= 200 && res.status < 300 && !servedSpaFallback;
            const missingNotFound = ENVOY_NOT_FOUND.has(path) && res.status !== 404;
            const violation = success || setsSession || missingNotFound;
            violations.add(violation ? 1 : 0);
            lines.push(`${violation ? 'VIOLATION' : 'ok'} ${method} ${path} -> ${res.status}${setsSession ? ' sets __Host-SESSION' : ''}${missingNotFound ? ' (Envoy not-found rule expected 404)' : ''}`);
        }
    }
    return { lines };
}

export default function () {}

export function handleSummary(data) {
    const lines = data.setup_data.lines;
    const bad = lines.filter((line) => line.startsWith('VIOLATION')).length;
    const text = `front-door probe: ${lines.length} requests, ${bad} violations\n${lines.join('\n')}\n`;
    return { [`${REPORT_DIR}/probe.txt`]: text, stdout: text };
}
