/**
 * The single place in `src/` where the API base is written down.
 *
 * THE BASE IS A RELATIVE PATH, AND IT DEFAULTS TO THE EMPTY STRING. The browser is
 * same-origin with the gateway in both environments -- Vite's `server.proxy` forwards
 * `/api`, `/.well-known`, `/v3/api-docs` and the Swagger paths in development, and one
 * nginx container `proxy_pass`es the same prefixes in production -- so every request this
 * app makes is a relative path like `/api/v1/orders` and no cross-origin request is ever
 * issued.
 *
 * That is not a convenience. The gateway REJECTS CORS PREFLIGHTS BEFORE ROUTING: an
 * `OPTIONS` with an `Origin` header returns a bodyless 403 plus the
 * `Vary: Origin / Access-Control-Request-Method / Access-Control-Request-Headers` triple,
 * on a routed path and on an unrouted one alike, because Spring's `DefaultCorsProcessor`
 * rejects a preflight that matches no `CorsConfiguration`. A browser reports that as an
 * opaque network error with nothing in it to debug. Staying same-origin means the code path
 * is never reached and `api-gateway/src/main/resources/application.yml` needs no change.
 *
 * WHY AN ENV VAR AT ALL, given that the base should be relative: so that a deployment which
 * mounts this app under a sub-path (`/shop`, behind a shared ingress) can say so in one
 * place instead of patching source. The variable can express a PATH PREFIX and nothing
 * else -- `assertRelativeBasePath` below rejects any value carrying a scheme or an
 * authority, so there is deliberately no way to point this client at another origin. If a
 * CDN deployment ever genuinely needs that, the answer is the CORS escape hatch recorded in
 * ADR 011, not a string in an env file, because that change also has to allowlist the
 * `Idempotency-Key` request header (omit it and CHECKOUT SPECIFICALLY breaks) and expose
 * `X-Correlation-Id` (omit it and the support code vanishes on exactly the gateway-level
 * errors that have no body to read it from).
 */

/** Name of the build-time variable, quoted in the error messages below. */
const ENV_VAR = 'VITE_API_BASE_PATH';

/**
 * Normalises and validates a configured base path.
 *
 * Exported because it is the one piece of logic in this file, and a rule that exists to
 * prevent a misconfiguration is worth a test.
 *
 * Accepts: `''` (the default), or an absolute path such as `/shop`. Trailing slashes are
 * stripped so that callers can always write `` `${API_BASE_PATH}/api/v1/orders` `` without
 * producing a double slash -- which the gateway's `Path=` predicates would not match.
 *
 * Rejects, loudly and at module load rather than at the first request:
 *  - anything containing `://` or starting with `//`, which is an absolute origin (a
 *    protocol-relative URL is absolute too, and it is the form that slips through a naive
 *    "does it start with http" check);
 *  - a relative path with no leading slash, which resolves against the CURRENT ROUTE and
 *    so produces a different URL on `/orders/123` than on `/`;
 *  - whitespace or a backslash, which mean a copy-paste accident rather than a decision.
 */
export function assertRelativeBasePath(raw: string): string {
  if (raw.trim() !== raw) {
    throw new Error(`${ENV_VAR} must not contain leading or trailing whitespace.`);
  }
  const value = raw;
  if (value === '' || value === '/') {
    return '';
  }
  if (value.includes('://') || value.startsWith('//')) {
    throw new Error(
      `${ENV_VAR} must be a path prefix, not an absolute origin. This app is served ` +
        `same-origin with the gateway; pointing it at another origin would issue a CORS ` +
        `preflight that the gateway answers with a bodyless 403.`,
    );
  }
  if (!value.startsWith('/')) {
    throw new Error(
      `${ENV_VAR} must start with "/" so that it resolves from the site root rather than ` +
        `from whichever route the user happens to be on.`,
    );
  }
  if (/[\s\\]/.test(value)) {
    throw new Error(`${ENV_VAR} must not contain whitespace or a backslash.`);
  }
  return value.replace(/\/+$/, '');
}

/**
 * The prefix every API path is built on. `''` in every environment this project ships.
 *
 * Read exactly once, here. No other module reads `import.meta.env`, and
 * `src/lib/api/http.ts` -- the only `fetch` in the app -- is the only expected consumer.
 */
export const API_BASE_PATH: string = assertRelativeBasePath(
  import.meta.env.VITE_API_BASE_PATH ?? '',
);
