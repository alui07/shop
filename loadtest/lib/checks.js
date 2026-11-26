import { check } from 'k6';
import { Counter } from 'k6/metrics';
import { TREND_STATS } from './config.js';

export const trendStats = TREND_STATS;

export const rateLimited = new Counter('rate_limited');

/** Merge into every scenario's thresholds: a 429 means the overlay did not raise the limits, so the run is void. */
export const rateLimitThreshold = { rate_limited: ['count==0'] };

/** Counts a 429 from any request made outside setup() (setup traffic carries load:setup); returns the response. */
export function countRateLimit(res, load) {
    if (res.status === 429 && load !== 'setup') rateLimited.add(1);
    return res;
}

export function statusIs(res, name, ...statuses) {
    return check(res, { [`${name} status ${statuses.join('/')}`]: (r) => statuses.includes(r.status) });
}

export function jsonBody(res) {
    try {
        return res.json();
    } catch (failure) {
        return null;
    }
}

export function problemType(res) {
    const body = jsonBody(res);
    return body && typeof body.type === 'string' ? body.type : '';
}

function metricLine(data, name, label) {
    const metric = data.metrics[name];
    if (!metric) return null;
    const v = metric.values;
    return `| ${label} | ${fmt(v.med)} | ${fmt(v['p(95)'])} | ${fmt(v['p(99)'])} | ${fmt(v.max)} |`;
}

function fmt(value) {
    return value === undefined ? '-' : Number(value).toFixed(1);
}

function thresholdRows(data) {
    const rows = [];
    for (const [name, metric] of Object.entries(data.metrics)) {
        for (const [expression, result] of Object.entries(metric.thresholds || {})) {
            rows.push(`| ${result.ok ? 'PASS' : 'FAIL'} | \`${name}\` | \`${expression}\` |`);
        }
    }
    return rows;
}

function valueOf(data, name, key) {
    const metric = data.metrics[name];
    return metric && metric.values[key] !== undefined ? metric.values[key] : null;
}

export function markdownSummary(scenario, data, extraMetrics = []) {
    const requests = valueOf(data, 'http_reqs{load:shop}', 'count') ?? valueOf(data, 'http_reqs', 'count');
    const rate = valueOf(data, 'http_reqs{load:shop}', 'rate') ?? valueOf(data, 'http_reqs', 'rate');
    const failed = valueOf(data, 'http_req_failed{load:shop}', 'rate');
    const lines = [
        `# k6 ${scenario}`,
        '',
        `Run ${__ENV.RUN_ID || 'adhoc'}. Requests (excluding session minting): ${requests}, ${fmt(rate)} req/s, unexpected-status rate ${failed === null ? '-' : (failed * 100).toFixed(2) + '%'}.`,
        '',
        '| request time (ms) | median | p95 | p99 | max |',
        '|---|---|---|---|---|',
        metricLine(data, 'http_req_duration{load:shop}', 'all shop requests'),
    ];
    for (const [metric, label] of extraMetrics) {
        const line = metricLine(data, metric, label);
        if (line) lines.push(line);
    }
    lines.push('', '| result | metric | threshold |', '|---|---|---|', ...thresholdRows(data));
    const counters = Object.entries(data.metrics)
        .filter(([name, metric]) => metric.type === 'counter' && /^(flash_|order_|checkout_)/.test(name))
        .map(([name, metric]) => `- ${name}: ${metric.values.count}`);
    if (counters.length) lines.push('', ...counters);
    const failedChecks = data.root_group.checks.filter((c) => c.fails > 0).map((c) => `- ${c.name}: ${c.fails} failed of ${c.passes + c.fails}`);
    lines.push('', failedChecks.length ? '## Failed checks' : 'All checks passed.', ...failedChecks, '');
    return lines.filter((line) => line !== null).join('\n');
}

export function summaryOutputs(scenario, data, extraMetrics) {
    const dir = __ENV.REPORT_DIR || '/reports';
    // setup_data holds the minted session cookies and XSRF tokens; it must never reach a report.
    const { setup_data: omittedSetupData, ...summary } = data;
    return {
        [`${dir}/${scenario}-summary.json`]: JSON.stringify(summary, null, 2),
        [`${dir}/${scenario}-summary.md`]: markdownSummary(scenario, data, extraMetrics),
        stdout: markdownSummary(scenario, data, extraMetrics),
    };
}
