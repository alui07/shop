# ADR 0004: Web security headers

Status: accepted, 2026-11-17

## Context

The web image is the first thing a browser talks to. Slice 1 enforces a strict Content Security Policy (CSP) and a standard header set on it, and adds a small header filter in the API.

## Decisions

**Content Security Policy, enforced on every response from the web image:**

```
default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self'; font-src 'self';
connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'
```

- `default-src 'self'`: anything not listed below may only load from our own origin.
- `script-src 'self'`: only the bundled scripts run. No inline scripts and no `eval`, which is what stops injected markup from executing.
- `style-src 'self'`: only bundled stylesheets. No inline `<style>` and no `style` attributes. Tailwind compiles to a stylesheet at build time, so none are needed.
- `img-src 'self'`: images only from our origin. The catalog has no product images yet and `data:` URIs are not needed, so they are not allowed. If a feature needs them, add them for that reason and record it here.
- `font-src 'self'`: the app uses the system font stack, no web fonts.
- `connect-src 'self'`: `fetch` and XHR may only reach our origin, which is where `/api` is served.
- `object-src 'none'`: no plugins.
- `base-uri 'self'`: stops an injected `<base>` tag from redirecting relative URLs.
- `form-action 'self'`: forms can only submit to our origin.
- `frame-ancestors 'none'`: nobody may embed the site in a frame (clickjacking). It is the modern counterpart of `X-Frame-Options`.

**Native selects, not Radix Select.** Category and sort use plain `<select>` elements. Radix Select injects a `<style>` element at runtime, which `style-src 'self'` blocks. We chose a native element over loosening the policy. The same rule applies to any future component: pick one that works under the CSP.

**Other headers**, sent with the CSP:
- `X-Content-Type-Options: nosniff`: the browser trusts declared content types.
- `Referrer-Policy: strict-origin-when-cross-origin`: other sites see only our origin.
- `X-Frame-Options: DENY`: framing protection for older browsers.
- `Cross-Origin-Opener-Policy: same-origin`: isolates our windows from cross-origin pages that open them.
- `Permissions-Policy`: camera, microphone, geolocation and payment are denied.

**nginx include pattern.** In nginx, an `add_header` inside a `location` discards every header inherited from the server block. So the header set lives in one snippet outside `conf.d` (`/etc/nginx/snippets/security-headers.conf`), which is included in the server block and in every location that adds its own header. All headers use `always`, so error responses carry them too. `server_tokens off` hides the nginx version.

**Caching.** `index.html` and the single-page-app fallback send `Cache-Control: no-cache`, so a new deploy is picked up. Hashed files under `/assets/` send `public, max-age=31536000, immutable`. `location /assets/` uses `try_files $uri =404`, so a missing hashed file is a real 404 and not `index.html` that a browser or proxy would then cache under a script URL.

**HSTS is not set here.** There is no TLS locally, and the header only means something where TLS is terminated. It belongs on the Gateway that terminates TLS (slice 2 and the cloud guide).

**The Vite dev server does not enforce the CSP.** Hot module reloading needs inline scripts. So `make dev` proves nothing about the policy. The policy is verified in e2e against the built image: a spec collects `securitypolicyviolation` events and console errors over the main flow and expects none, and another checks the header set. `tests/manual/README.md` has a browser dev-tools check as well.

**API headers until Spring Security (slice 3).** The API does not have Spring Security yet. A small servlet filter adds `X-Content-Type-Options: nosniff` to every response and logs one line per request (method, path, status, duration). Catalog GET responses send `Cache-Control: no-cache`, meaning "revalidate", not `no-store`. Slice 3 replaces the filter's headers with Spring Security's header writer.

## Consequences

Any change to what the page loads (images, fonts, third-party scripts, a component library that injects styles) is a CSP decision, and e2e fails on violations. The API and web image send different header sets until slice 3 aligns them.
