// `defineConfig` comes from 'vitest/config', not 'vite': it is Vite's own helper re-exported
// with the `test` key added to the type. Importing it from 'vite' instead is the mistake that
// produces "'test' does not exist in type 'UserConfigExport'" -- and the reason the Vitest
// config lives in this file at all is that there is ONE toolchain here, not two.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * The ONE place in this project where an absolute API origin is written down, and it is
 * dev-server configuration -- it is never bundled, never reachable from `src/`, and the
 * eslint `no-restricted-syntax` rule that bans `http://localhost:8` literals is scoped to
 * `src/` precisely so this line can exist here and nowhere else.
 *
 * Do not "helpfully" move this into src/config.ts. The application's API base is a
 * RELATIVE path (see src/config.ts); the browser is same-origin with the API in both
 * environments, and that is the whole CORS answer.
 */
const GATEWAY_ORIGIN = 'http://localhost:8080';

/**
 * Prefixes the dev server forwards to the gateway. Keys are matched as prefixes, so
 * `/api` covers every `/api/v1/**` route.
 *
 * `/swagger-ui.html` is listed separately from `/swagger-ui` even though the latter would
 * match it as a prefix: the gateway answers `/swagger-ui.html` with a 302 into
 * `/swagger-ui/index.html` (verified live), and naming both makes it obvious that the
 * redirect target is proxied too rather than leaking to the dev server's SPA fallback.
 *
 * `/v3/api-docs` covers `/v3/api-docs/swagger-config`, which springdoc serves from the
 * gateway itself -- api-gateway's own application.yml comments on why that path must not
 * be swallowed by a wildcard, and the same care applies on this side of the wire.
 */
const PROXIED_PREFIXES = [
  '/api',
  '/.well-known',
  '/v3/api-docs',
  '/swagger-ui.html',
  '/swagger-ui',
] as const;

const proxy = Object.fromEntries(
  PROXIED_PREFIXES.map((prefix) => [
    prefix,
    {
      target: GATEWAY_ORIGIN,

      // FALSE, deliberately. The gateway routes on PATH only -- every predicate in
      // api-gateway/src/main/resources/application.yml is a `Path=` predicate, with no
      // Host predicate anywhere -- so rewriting Host buys nothing, and
      // CorrelationIdGlobalFilter's request logging is more useful when it sees the
      // browser's real Host (`localhost:5173`) than when it sees one we invented.
      changeOrigin: false,
    },
  ]),
);

export default defineConfig({
  plugins: [react()],

  server: {
    // 5173 and not 3000: docker-compose.yml already publishes Grafana on 3000, and a dev
    // server that silently hops to the next free port is a dev server whose proxy URL you
    // have to go and look up.
    port: 5173,
    strictPort: true,

    /**
     * THE DEV CORS ANSWER, and it is an answer by AVOIDANCE rather than by configuration.
     *
     * The browser talks only to `http://localhost:5173`, which forwards the prefixes below
     * to the gateway, so every request the app makes is SAME-ORIGIN and the browser never
     * issues a preflight. That matters because the gateway rejects preflights before
     * routing: `OPTIONS /api/v1/products` with an Origin returns 403 plus the
     * `Vary: Origin / Access-Control-Request-Method / Access-Control-Request-Headers`
     * triple and an empty body, and so does `OPTIONS /nope`, an unrouted path -- which is
     * Spring's `DefaultCorsProcessor.rejectRequest()` firing in `AbstractHandlerMapping`
     * with no matching `CorsConfiguration`, not any service's security filter chain.
     *
     * So the gateway needs NO change: api-gateway/src/main/resources/application.yml is
     * not edited by this project at all, and CORS is never widened. Three further
     * dividends fall out of being same-origin: the browser can read the echoed
     * `X-Correlation-Id` with no `Access-Control-Expose-Headers` entry to forget, the
     * `Idempotency-Key` request header needs no allowlist (omit it and CHECKOUT
     * SPECIFICALLY breaks, as an opaque network error), and a CSP of `connect-src 'self'`
     * is writable at all.
     */
    proxy,
  },

  build: {
    /*
     * 'hidden', not true. Errors in this app are reported by a user quoting a support code,
     * so a map that can turn a minified frame back into a file and a line is worth having --
     * but `true` also appends a `//# sourceMappingURL=` comment, which makes every browser
     * that opens devtools pull a 1.7 MB file the production nginx would then have to serve.
     * 'hidden' writes the map into dist/ for whoever needs to symbolicate and leaves the
     * bundle with no pointer to it.
     */
    sourcemap: 'hidden',
  },

  /**
   * Vitest reuses this config on purpose: one toolchain, one resolver, one set of
   * transforms. That is the whole reason Jest is not here -- a second transform pipeline
   * that has to be taught the same things Vite already knows.
   */
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],

    // Explicitly NOT `passWithNoTests`. A glob that stops matching -- a renamed directory,
    // a typo in a stage's test path -- must fail the run rather than pass it silently.
    passWithNoTests: false,

    // CSS Modules resolve to a proxy that returns the key name, which is all any test here
    // needs (`styles.row` is truthy and stable). Actually compiling CSS in jsdom buys
    // nothing because jsdom does not lay anything out, and costs time on every run.
    css: false,

    clearMocks: true,
    restoreMocks: true,

    coverage: {
      provider: 'v8',
      reporter: ['text', 'html'],

      /**
       * A deliberate PARTIAL adoption of the backend's JaCoCo-floor convention, not a
       * rejection of it. The backend's floors guard logic money depends on; here that
       * logic is the problem parser, the status tables, the idempotency-key lifetime and
       * the cart -- not the markup. A floor across `.tsx` buys tests that assert a `<div>`
       * is a `<div>`, so `.tsx` is excluded and the floor applies to the three directories
       * where a bug is expensive.
       */
      include: ['src/lib/api/**', 'src/domain/**', 'src/lib/cart/**'],
      exclude: ['**/*.tsx', '**/__tests__/**'],
      thresholds: {
        lines: 80,
        functions: 80,
        branches: 80,
        statements: 80,
      },
    },
  },
});
