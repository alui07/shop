import { z } from 'zod';

// Zod probes for eval support with `new Function`, which the enforced CSP reports as a script-src violation.
z.config({ jitless: true });

export { z };
