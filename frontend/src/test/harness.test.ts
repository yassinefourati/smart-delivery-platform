import { describe, expect, it } from 'vitest';

import { API_BASE_PATH, assertRelativeBasePath } from '../config';

/**
 * Tests for the harness itself, and for the one rule the foundation's own config carries.
 *
 * This file exists at this stage for a plain reason: a test suite with nothing in it cannot
 * tell you whether it works, and `passWithNoTests` is off precisely so that an empty run is a
 * failure. Everything asserted here is something a later stage would otherwise discover the
 * hard way -- jsdom missing, the DOM matchers not registered against Vitest's `expect`, or a
 * base path that quietly points the client at another origin.
 *
 * The five high-value tests of this project -- the problem parser, `nextPollDelay`,
 * `canCancel`, the idempotency-key matrix and the guards -- belong to the modules they cover
 * and arrive with them.
 */

describe('test harness', () => {
  it('runs in a DOM environment', () => {
    // If this fails, `environment: 'jsdom'` was lost from vite.config.ts and every component
    // test in the project is about to fail with a much less obvious message.
    expect(typeof document).toBe('object');
    expect(document.body).toBeTruthy();
  });

  it('has the jest-dom matchers registered against vitest expect', () => {
    const el = document.createElement('p');
    el.textContent = 'harness';
    document.body.append(el);

    // `toBeInTheDocument` comes from '@testing-library/jest-dom/vitest' in src/test/setup.ts.
    expect(el).toBeInTheDocument();
    el.remove();
  });

  it('stubs matchMedia, which jsdom does not implement', () => {
    expect(window.matchMedia('(prefers-reduced-motion: reduce)').matches).toBe(false);
  });

  it('exposes build-time env through import.meta.env', () => {
    // Not an assertion about a value -- an assertion that the Vite transform ran at all. If
    // `import.meta.env` were undefined, src/config.ts would throw at import time and the
    // failure would look like a config bug rather than a toolchain one.
    expect(import.meta.env).toBeDefined();
  });
});

describe('API base path', () => {
  it('defaults to the empty string, meaning same-origin with no prefix', () => {
    // The whole CORS design rests on this being relative. See src/config.ts.
    expect(API_BASE_PATH).toBe('');
  });

  it.each([
    ['', ''],
    ['/', ''],
    ['/shop', '/shop'],
    ['/shop/', '/shop'],
    ['/shop///', '/shop'],
  ])('normalises %o to %o', (raw, expected) => {
    expect(assertRelativeBasePath(raw)).toBe(expected);
  });

  it.each([
    // An absolute origin -- the value this client must have no way to express, because
    // pointing it off-origin issues a preflight the gateway answers with a bodyless 403.
    ['https://api.example.test'],
    // Protocol-relative, which is absolute too and is the form a "starts with http" check
    // lets through.
    ['//api.example.test'],
    // No leading slash: resolves against the current route, so it means one URL on `/` and a
    // different one on `/orders/123`.
    ['api'],
    ['  /shop'],
    ['/sh op'],
  ])('rejects %o', (raw) => {
    expect(() => assertRelativeBasePath(raw)).toThrow();
  });
});
