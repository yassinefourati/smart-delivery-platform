/**
 * The MSW server these tests run against, plus the response builders they need.
 *
 * WHY MSW AND NOT A `vi.fn()` FETCH STUB. Everything worth testing in this layer is HTTP
 * BEHAVIOUR: whether a charset-suffixed content type is recognised as problem+json, whether a
 * duplicated `X-Correlation-Id` header yields one id or two, whether a schema mismatch becomes a
 * CONTRACT_VIOLATION with a field path, whether the `Idempotency-Key` header actually goes out.
 * Against a stub, all of those become assertions about the ARGUMENTS A MOCK WAS CALLED WITH --
 * that is testing the mock. MSW lets a test say "the server returns 409 with THIS captured body"
 * and then assert what the CODE does with it, through real `Request` and `Response` objects.
 *
 * THIS FILE, RATHER THAN src/test/msw/server.ts, because src/test/ belongs to another stage and
 * src/test/setup.ts carries the MSW block as a documented extension point for it to wire up
 * globally. A per-file server needs no coordination and works today. When the global server lands,
 * these tests keep working: `server.listen()` is idempotent per file.
 *
 * `onUnhandledRequest: 'error'` is the load-bearing option. Without it, a request no handler
 * covers escapes as a real network call that fails far from its cause, and the test reports a
 * timeout instead of "nothing mocks GET /api/v1/orders/:id/status".
 */

import { setupServer } from 'msw/node';
import { HttpResponse } from 'msw';
import { CONTENT_TYPE } from './fixtures';

export { http } from 'msw';
export { HttpResponse };

/** Started empty: every test declares the handlers it needs with `server.use(...)`. */
export const server = setupServer();

/**
 * Register the lifecycle hooks. Called once at the top of each test file.
 *
 * `resetHandlers` after each test is what keeps a one-off `server.use(...)` -- the 409 case, the
 * 503 case -- from leaking into the next test in the file and passing it for the wrong reason.
 */
export function setupTestServer(): void {
  beforeAll(() => {
    server.listen({ onUnhandledRequest: 'error' });
  });
  afterEach(() => {
    server.resetHandlers();
  });
  afterAll(() => {
    server.close();
  });
}

/**
 * A problem+json response with the content type spelled EXACTLY as given.
 *
 * `HttpResponse.json` would set a bare `application/json` and silently defeat the thing under
 * test, so the body is stringified here and the header written by hand.
 */
export function problemResponse(
  status: number,
  body: unknown,
  contentType: string = CONTENT_TYPE.PROBLEM_BARE,
  extraHeaders: Readonly<Record<string, string>> = {},
): Response {
  return new HttpResponse(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': contentType, ...extraHeaders },
  });
}

/**
 * A response carrying `X-Correlation-Id` TWICE, as the deployed gateway really does.
 *
 * `Headers.append` twice is what produces the duplicate; the Fetch spec then joins the values with
 * a comma on `get()`, so the client reads `"<id>, <id>"`. This builder exists so the regression
 * test is about the real mechanism rather than about a hand-written `"a, b"` string.
 */
export function problemResponseWithDuplicatedCorrelationHeader(
  status: number,
  body: unknown,
  correlationId: string,
  contentType: string = CONTENT_TYPE.PROBLEM_ISO,
): Response {
  const headers = new Headers({ 'Content-Type': contentType });
  headers.append('X-Correlation-Id', correlationId);
  headers.append('X-Correlation-Id', correlationId);
  return new HttpResponse(JSON.stringify(body), { status, headers });
}

/** A successful JSON response, with the content type the gateway sends on a 2xx. */
export function jsonResponse(body: unknown, status = 200): Response {
  return new HttpResponse(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': CONTENT_TYPE.JSON },
  });
}

/** 204, empty body -- what all four DELETEs in this API actually answer. */
export function noContentResponse(): Response {
  return new HttpResponse(null, { status: 204 });
}
