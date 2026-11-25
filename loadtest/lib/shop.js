import http from 'k6/http';
import { sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';
import { BASE_URL, PAID_POLL_INTERVAL_S, PAID_POLL_LIMIT } from './config.js';
import { authHeaders } from './session.js';
import { countRateLimit, jsonBody, problemType, statusIs } from './checks.js';

export const timeToPaid = new Trend('time_to_paid', true);
export const orderPaid = new Rate('order_paid');
export const checkoutDuration = new Trend('checkout_duration', true);
export const outOfStock = new Counter('flash_out_of_stock');
export const otherConflicts = new Counter('flash_other_conflicts');

const OUT_OF_STOCK_TYPE = 'tag:shop.dev,2026:out-of-stock';
const SUCCESS = http.expectedStatuses({ min: 200, max: 399 });
const CREATED_OR_CONFLICT = http.expectedStatuses(201, 409);

const SORTS = ['name', 'price_asc', 'price_desc'];
const SEARCH_WORDS = ['oak', 'steel', 'linen', 'lamp', 'garden', 'bowl', 'cotton', 'brass'];

function pick(list) {
    return list[Math.floor(Math.random() * list.length)];
}

function tags(op, load) {
    return { name: op, op, load };
}

function get(path, op, load, headers = {}) {
    return countRateLimit(http.get(`${BASE_URL}${path}`, { headers, tags: tags(op, load), responseCallback: SUCCESS }), load);
}

export function uuid() {
    const hex = () => Math.floor(Math.random() * 16).toString(16);
    return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => (c === 'x' ? hex() : (8 + Math.floor(Math.random() * 4)).toString(16)));
}

/** Reads the catalog the way a visitor would; `catalog` is {slugs, categories} gathered in setup. */
export function browseVisit(catalog, load = 'shop') {
    statusIs(get('/api/me', 'GET /api/me', load), 'me', 200);
    statusIs(get('/api/categories', 'GET /api/categories', load), 'categories', 200);
    statusIs(get(listPath(catalog), 'GET /api/products', load), 'product list', 200);
    statusIs(get(`/api/products/${pick(catalog.slugs)}`, 'GET /api/products/{slug}', load), 'product detail', 200);
    if (Math.random() < 0.2) {
        statusIs(get('/', 'GET /', load), 'home page', 200);
    }
}

function listPath(catalog) {
    const params = [`page=${Math.floor(Math.random() * 3)}`, `size=${pick([12, 24])}`];
    const roll = Math.random();
    if (roll < 0.1) {
        params.push(`q=${pick(SEARCH_WORDS)}`);
    } else {
        params.push(`sort=${pick(SORTS)}`);
        if (roll < 0.5) params.push(`category=${pick(catalog.categories)}`);
    }
    return `/api/products?${params.join('&')}`;
}

/** Lists the visible catalog for browse scenarios, leaving out the load test's own products. */
export function discoverCatalog() {
    const categories = get('/api/categories', 'setup categories', 'setup').json().map((c) => c.slug);
    const slugs = [];
    let page = 0;
    let totalPages = 1;
    while (page < totalPages) {
        const body = get(`/api/products?size=48&page=${page}`, 'setup products', 'setup').json();
        totalPages = body.totalPages;
        body.items.forEach((item) => slugs.push(item.slug));
        page += 1;
    }
    const visible = slugs.filter((slug) => !slug.startsWith('lt-') && !slug.startsWith('flash-'));
    if (visible.length === 0) {
        throw new Error('the catalog has no products to browse; is the seed profile on?');
    }
    return { slugs: visible, categories };
}

export function putCartLine(session, slug, quantity) {
    return countRateLimit(
        http.put(`${BASE_URL}/api/cart/lines/${slug}`, JSON.stringify({ quantity }), {
            headers: authHeaders(session, { 'Content-Type': 'application/json' }),
            tags: tags('PUT /api/cart/lines/{slug}', 'shop'),
            responseCallback: SUCCESS,
        }),
    );
}

/** Places an order for the current cart; `allowConflict` makes 409 an expected status (flash sale). */
export function checkout(session, cart, allowConflict) {
    const res = http.post(
        `${BASE_URL}/api/checkout`,
        JSON.stringify({ cartVersion: cart.version, expectedTotalMinor: cart.total.amountMinor }),
        {
            headers: authHeaders(session, { 'Content-Type': 'application/json', 'Idempotency-Key': uuid() }),
            tags: { ...tags('POST /api/checkout', 'shop'), op: 'checkout' },
            responseCallback: allowConflict ? CREATED_OR_CONFLICT : http.expectedStatuses(201),
        },
    );
    checkoutDuration.add(res.timings.duration);
    return countRateLimit(res);
}

export function classifyConflict(res) {
    if (problemType(res) === OUT_OF_STOCK_TYPE) {
        outOfStock.add(1);
    } else {
        otherConflicts.add(1);
    }
}

function post(session, path, op, body) {
    return countRateLimit(
        http.post(`${BASE_URL}${path}`, body === undefined ? null : JSON.stringify(body), {
            headers: authHeaders(session, body === undefined ? {} : { 'Content-Type': 'application/json' }),
            tags: tags(op, 'shop'),
            responseCallback: SUCCESS,
        }),
    );
}

/** Starts the payment, completes it on the fake provider's API and returns once the order is PAID or the wait ran out. */
export function payAndAwait(session, orderId) {
    const started = Date.now();
    const start = post(session, `/api/orders/${orderId}/payment`, 'POST /api/orders/{id}/payment');
    if (!statusIs(start, 'start payment', 200)) return false;
    const token = jsonBody(start).redirectUrl.split('/').pop();
    const page = get(`/api/fake-psp/payments/${token}`, 'GET /api/fake-psp/payments/{token}', 'shop', authHeaders(session));
    statusIs(page, 'read payment', 200);
    const done = post(session, `/api/fake-psp/payments/${token}/complete`, 'POST /api/fake-psp/payments/{token}/complete', {
        outcome: 'SUCCEEDED',
    });
    if (!statusIs(done, 'complete payment', 200)) return false;
    return awaitPaid(session, orderId, started);
}

function awaitPaid(session, orderId, started) {
    for (let poll = 0; poll < PAID_POLL_LIMIT; poll++) {
        const res = get(`/api/orders/${orderId}`, 'GET /api/orders/{id}', 'shop', authHeaders(session));
        statusIs(res, 'read order', 200);
        if (jsonBody(res) && jsonBody(res).status === 'PAID') {
            timeToPaid.add(Date.now() - started);
            orderPaid.add(true);
            return true;
        }
        sleep(PAID_POLL_INTERVAL_S);
    }
    orderPaid.add(false);
    return false;
}
