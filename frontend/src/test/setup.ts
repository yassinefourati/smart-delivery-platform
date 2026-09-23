import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach, beforeAll } from 'vitest';

/**
 * Vitest setup, run once per test file before any test in it.
 *
 * `@testing-library/jest-dom/vitest` registers the DOM matchers (`toBeInTheDocument`,
 * `toBeDisabled`, `toHaveAccessibleName`) against Vitest's `expect` rather than Jest's --
 * importing the plain `@testing-library/jest-dom` entry point instead is the mistake that
 * produces "toBeInTheDocument is not a function" in a project that looks correctly
 * configured.
 *
 * WHAT DELIBERATELY IS NOT HERE: no `fetch` mock, no `vi.stubGlobal('fetch', ...)`. Tests in
 * this project assert against MSW request handlers (see the extension point below), so a test
 * can say "the server returns a 409 with THIS body" and then assert what the USER sees.
 * Asserting on the arguments a `vi.fn()` fetch stub was called with is testing the mock.
 */

afterEach(() => {
  /*
   * Explicit, rather than relying on Testing Library's automatic cleanup. RTL does register
   * an `afterEach` of its own when a global `afterEach` exists, but that behaviour depends on
   * `test.globals` staying enabled -- and a leaked component between tests shows up as a
   * "found multiple elements" failure in an unrelated test, which is a bad afternoon. One
   * line here removes the dependency.
   */
  cleanup();
});

beforeAll(() => {
  /*
   * jsdom implements neither `matchMedia` nor the layout that would make it meaningful, and
   * this app asks about `prefers-reduced-motion`. Left unstubbed, the first component that
   * consults it throws `window.matchMedia is not a function` -- a failure about the
   * environment, in a test about behaviour.
   *
   * THE FEATURE TEST IS `typeof`, NOT `in`, and that is not pedantry: jsdom 27 defines a
   * `matchMedia` ACCESSOR on `window` whose getter returns `undefined`, so
   * `'matchMedia' in window` is TRUE while `window.matchMedia(...)` still throws. Verified by
   * reading the property descriptor in this project's own jsdom. An `in` check here installs
   * nothing and the stub silently does not exist.
   *
   * The stub answers "no preference" to everything, which is the right default: a test that
   * cares about reduced motion should override `matches` itself and say so.
   */
  if (typeof window.matchMedia !== 'function') {
    Object.defineProperty(window, 'matchMedia', {
      writable: true,
      value: (query: string): MediaQueryList => ({
        matches: false,
        media: query,
        onchange: null,
        addEventListener: () => {},
        removeEventListener: () => {},
        addListener: () => {},
        removeListener: () => {},
        dispatchEvent: () => false,
      }),
    });
  }
});

/*
 * EXTENSION POINT -- the MSW server.
 *
 * When src/test/msw/server.ts lands, add exactly this and nothing more:
 *
 *     import { server } from './msw/server';
 *
 *     beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
 *     afterEach(() => server.resetHandlers());
 *     afterAll(() => server.close());
 *
 * `onUnhandledRequest: 'error'` is the load-bearing option: without it, a request to a path
 * no handler covers resolves as a real network call that fails somewhere far from its cause,
 * and the test reports a timeout instead of "nothing mocks GET /api/v1/orders/:id/status".
 * `resetHandlers` per test keeps a one-off `server.use(...)` -- the 409 case, the 503 case --
 * from leaking into the next test in the file.
 *
 * Fixtures come from src/test/fixtures/, captured VERBATIM from the running gateway: the real
 * 401 body with its charset-suffixed problem+json content type, the real bare-content-type
 * 409, a real ProductPageResponse with `imageUrl: null`, a real InventorySummaryResponse with
 * `totalAvailable: 0`. Invented fixtures are how a test suite drifts into fiction about a
 * contract it was never shown.
 */
