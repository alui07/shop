// Shared settings. Every value that a scenario or the Makefile may tune is read from the environment here, once.
export const BASE_URL = __ENV.BASE_URL || 'http://shop-control-plane:30080';
export const MINT_URL = __ENV.MINT_URL || 'http://shop-control-plane:30090';
export const RUN_ID = __ENV.RUN_ID || 'adhoc';
export const LOADTEST_TOKEN = __ENV.SHOP_LOADTEST_TOKEN || '';
export const REPORT_DIR = __ENV.REPORT_DIR || '/reports';

export const SESSION_COOKIE = '__Host-SESSION';
export const XSRF_COOKIE = '__Host-XSRF-TOKEN';

// A minted session ends at its first revalidation, 5 minutes after the mint (brief 2.2): refresh well before.
export const SESSION_MAX_AGE_MS = 3.5 * 60 * 1000;
export const MINT_RETRY_LIMIT = 60;

export const TREND_STATS = ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'];

export const BROWSE_RATE = Number(__ENV.RATE || 30);
export const BROWSE_DURATION = __ENV.DURATION || '2m';
export const BROWSE_WARMUP = __ENV.WARMUP || '0s';

export const CHECKOUT_PRODUCT_COUNT = 5;
export const CHECKOUT_STAGES = [
    { duration: '30s', target: 5 },
    { duration: '2m30s', target: 20 },
];
export const PAID_POLL_INTERVAL_S = 1;
export const PAID_POLL_LIMIT = 20;

export const FLASH_UNITS = 100;
export const FLASH_BURST_VUS = 150;
export const FLASH_BURST_DURATION = '2m';
// Anonymous browse during the flash-sale hold, about twice the load that reaches the HPA's 60% CPU target.
// TO BE CALIBRATED on the first runs (hpa.csv shows CPU %); the calibrated value goes in docs/performance.md.
export const FLASH_HOLD_BROWSE_RATE = Number(__ENV.FLASH_HOLD_RATE || 60);
export const FLASH_HOLD_CHECKOUT_VUS = 10;
export const FLASH_HOLD_START = '1m';
export const FLASH_HOLD_DURATION = '3m';
