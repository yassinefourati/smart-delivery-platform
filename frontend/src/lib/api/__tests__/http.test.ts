/**
 * The one `fetch` in the app, tested through MSW against real `Request` and `Response` objects.
 *
 * The assertions here are about what LEAVES the browser (the correlation id, the
 * `Idempotency-Key`, the relative URL, the absence of an `Authorization` header until one is
 * registered) and about what happens to what comes back (problem parsing end to end, contract
 * violations with a field path, the abort). Those are the properties every feature stage inherits
 * without being able to see them.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import { z } from 'zod';
import { delay } from 'msw';
import {
  buildUrl,
  expandPathTemplate,
  registerRequestObserver,
  request,
  requestNoContent,
  setAuthorizationAttacher,
  summariseIssues,
} from '../http';
import type { RequestRecord } from '../http';
import { ApiProblem } from '../problem';
import { orderResponseSchema } from '../schemas/order';
import {
  http,
  jsonResponse,
  noContentResponse,
  problemResponse,
  problemResponseWithDuplicatedCorrelationHeader,
  server,
  setupTestServer,
  HttpResponse,
} from './testServer';
import {
  CONTENT_TYPE,
  ORDER_RESPONSE_CREATED,
  PRODUCT_PAGE_RESPONSE,
  PROBLEM_401,
} from './fixtures';

setupTestServer();

beforeEach(() => {
  // Every test starts with no token attacher, which is also the app's state before
  // src/lib/auth/tokenStore.ts registers one. A protected call then fails closed with a 401.
  setAuthorizationAttacher(null);
  // http.ts logs method, path template, status, code and correlation id on every failure. The
  // failure paths are most of this file, so the log is silenced rather than read.
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

const echoSchema = z.object({ ok: z.boolean() });

/**
 * Await a request that must fail, and hand back the `ApiProblem`.
 *
 * Written as a helper rather than `.catch(e => e as ApiProblem)` at each call site because the cast
 * would also swallow the case this file most needs to catch: a request that SUCCEEDS when the test
 * says it should not. This throws instead of quietly asserting against a success value.
 */
async function problemFrom(pending: Promise<unknown>): Promise<ApiProblem> {
  try {
    await pending;
  } catch (error) {
    if (error instanceof ApiProblem) {
      return error;
    }
    throw error;
  }
  throw new Error('expected the request to fail, and it succeeded');
}

describe('what goes out', () => {
  it('sends a fresh X-Correlation-Id on every request, and never the same one twice', async () => {
    const seen: string[] = [];
    server.use(
      http.get('/api/v1/ping', ({ request: incoming }) => {
        seen.push(incoming.headers.get('X-Correlation-Id') ?? '');
        return jsonResponse({ ok: true });
      }),
    );

    await request({ method: 'GET', pathTemplate: '/api/v1/ping' }, echoSchema);
    await request({ method: 'GET', pathTemplate: '/api/v1/ping' }, echoSchema);

    // Sent OUTBOUND, before there is a response to read one from -- which is the only reason a
    // gateway 504 or a rejected fetch has a support code at all. CorrelationIdGlobalFilter
    // forwards a client-supplied id rather than minting its own, so this id is already in the
    // server's logs.
    expect(seen).toHaveLength(2);
    expect(seen[0]).toMatch(/^[0-9a-f-]{36}$/);
    expect(seen[0]).not.toBe(seen[1]);
  });

  it('accepts both a JSON success and a problem+json failure', async () => {
    let accept = '';
    server.use(
      http.get('/api/v1/ping', ({ request: incoming }) => {
        accept = incoming.headers.get('Accept') ?? '';
        return jsonResponse({ ok: true });
      }),
    );

    await request({ method: 'GET', pathTemplate: '/api/v1/ping' }, echoSchema);

    expect(accept).toContain('application/json');
    expect(accept).toContain('application/problem+json');
  });

  it('sets Content-Type only when there is a body', async () => {
    const types: (string | null)[] = [];
    server.use(
      http.get('/api/v1/ping', ({ request: incoming }) => {
        types.push(incoming.headers.get('Content-Type'));
        return jsonResponse({ ok: true });
      }),
      http.post('/api/v1/ping', ({ request: incoming }) => {
        types.push(incoming.headers.get('Content-Type'));
        return jsonResponse({ ok: true });
      }),
    );

    await request({ method: 'GET', pathTemplate: '/api/v1/ping' }, echoSchema);
    await request({ method: 'POST', pathTemplate: '/api/v1/ping', body: { a: 1 } }, echoSchema);

    expect(types[0]).toBeNull();
    expect(types[1]).toBe('application/json');
  });

  it('forwards the Idempotency-Key as a literal header', async () => {
    let key: string | null = null;
    server.use(
      http.post('/api/v1/orders', ({ request: incoming }) => {
        key = incoming.headers.get('Idempotency-Key');
        return jsonResponse(ORDER_RESPONSE_CREATED, 201);
      }),
    );

    await request(
      {
        method: 'POST',
        pathTemplate: '/api/v1/orders',
        body: { shippingAddressId: 'a', items: [] },
        idempotencyKey: 'key-from-the-persisted-cart',
      },
      orderResponseSchema,
    );

    expect(key).toBe('key-from-the-persisted-cart');
  });

  it('sends no Authorization header until tokenStore registers an attacher', async () => {
    const headers: (string | null)[] = [];
    server.use(
      http.get('/api/v1/ping', ({ request: incoming }) => {
        headers.push(incoming.headers.get('Authorization'));
        return jsonResponse({ ok: true });
      }),
    );

    await request({ method: 'GET', pathTemplate: '/api/v1/ping' }, echoSchema);

    // Fails CLOSED. A protected call with no token gets a 401 the app knows how to handle, rather
    // than appearing to work.
    setAuthorizationAttacher((toSet) => {
      toSet.set('Authorization', 'Bearer attached-by-tokenStore');
    });
    await request({ method: 'GET', pathTemplate: '/api/v1/ping' }, echoSchema);

    expect(headers[0]).toBeNull();
    expect(headers[1]).toBe('Bearer attached-by-tokenStore');
  });

  it('builds a same-origin relative URL, with no way to express another origin', async () => {
    let url = '';
    server.use(
      http.get('/api/v1/products', ({ request: incoming }) => {
        url = incoming.url;
        return jsonResponse(PRODUCT_PAGE_RESPONSE);
      }),
    );

    await request(
      {
        method: 'GET',
        pathTemplate: '/api/v1/products',
        query: { page: 0, size: 2, sort: 'price,desc' },
      },
      z.object({ totalPages: z.number() }),
    );

    // The request resolved against the DOCUMENT's origin because the path was relative. That is
    // the whole CORS answer: the browser is never cross-origin to the API, so no preflight is
    // ever issued and the gateway's bodyless-403 preflight rejection is never reached.
    const parsed = new URL(url);
    expect(parsed.origin).toBe(globalThis.location.origin);
    expect(parsed.pathname).toBe('/api/v1/products');
    expect(parsed.searchParams.get('page')).toBe('0');
    expect(parsed.searchParams.get('sort')).toBe('price,desc');
  });
});

describe('URL building', () => {
  it('fills path parameters and percent-encodes each segment', () => {
    expect(
      buildUrl({ pathTemplate: '/api/v1/orders/:orderId/status', params: { orderId: 'abc-123' } }),
    ).toBe('/api/v1/orders/abc-123/status');

    // An id containing a slash must not escape its segment and address a different endpoint.
    expect(
      buildUrl({ pathTemplate: '/api/v1/products/:productId', params: { productId: 'a/../b' } }),
    ).toBe('/api/v1/products/a%2F..%2Fb');
  });

  it('omits undefined query members instead of sending an empty value', () => {
    // `?minPrice=` is a VALUE, which Spring tries to bind. An absent parameter is what the server
    // treats as a default.
    expect(
      buildUrl({
        pathTemplate: '/api/v1/products',
        query: { search: 'widget', minPrice: undefined, page: 0 },
      }),
    ).toBe('/api/v1/products?search=widget&page=0');
  });

  it('produces no trailing question mark when every member is undefined', () => {
    expect(buildUrl({ pathTemplate: '/api/v1/products', query: { search: undefined } })).toBe(
      '/api/v1/products',
    );
  });

  it('throws a plain Error -- not an ApiProblem -- for a missing path parameter', () => {
    // No request was made, so there is nothing for a user to quote. Failing here beats calling
    // `/api/v1/orders/undefined` and reading the 404 as "your order does not exist".
    expect(() => expandPathTemplate('/api/v1/orders/:orderId', {})).toThrow(
      /Missing path parameter ":orderId"/,
    );
    expect(() => expandPathTemplate('/api/v1/orders/:orderId', {})).not.toThrow(ApiProblem);
  });
});

describe('what comes back', () => {
  it('turns a 401 into an ApiProblem with exactly one correlation id', async () => {
    // End to end through the real fetch pipeline, with the header duplicated the way the deployed
    // gateway duplicates it, and the charset suffix that defeats an `===` content-type check.
    const id = '09c8712c-dcb9-482c-b460-15513dd89c06';
    server.use(
      http.get('/api/v1/orders/:orderId', () =>
        problemResponseWithDuplicatedCorrelationHeader(401, PROBLEM_401, id),
      ),
    );

    const problem = await problemFrom(
      request(
        { method: 'GET', pathTemplate: '/api/v1/orders/:orderId', params: { orderId: 'x' } },
        orderResponseSchema,
      ),
    );

    expect(problem).toBeInstanceOf(ApiProblem);
    expect(problem.code).toBe('UNAUTHORIZED');
    expect(problem.correlationId).toBe(id);
    expect(problem.correlationId).not.toContain(',');
  });

  it('parses a successful body through its schema', async () => {
    server.use(http.get('/api/v1/orders/:orderId', () => jsonResponse(ORDER_RESPONSE_CREATED)));

    const order = await request(
      { method: 'GET', pathTemplate: '/api/v1/orders/:orderId', params: { orderId: 'x' } },
      orderResponseSchema,
    );

    expect(order.id).toBe(ORDER_RESPONSE_CREATED.id);
    expect(order.status).toBe('CREATED');
    expect(order.items[0]?.lineTotal).toBe(50);
  });

  it('raises CONTRACT_VIOLATION naming the field that moved', async () => {
    // The failure a hand-written `interface` would have hidden: the field is gone, so every reader
    // of it sees `undefined` at render time in a component three levels from the fetch. Here it
    // fails at the fetch, with a path.
    const { totalAmount: _dropped, ...withoutTotal } = ORDER_RESPONSE_CREATED;
    server.use(http.get('/api/v1/orders/:orderId', () => jsonResponse(withoutTotal)));

    const problem = await problemFrom(
      request(
        { method: 'GET', pathTemplate: '/api/v1/orders/:orderId', params: { orderId: 'x' } },
        orderResponseSchema,
      ),
    );

    expect(problem.code).toBe('CONTRACT_VIOLATION');
    expect(problem.detail).toContain('totalAmount');
    // Identical in development and in production. A parser that throws in dev and degrades in prod
    // is how a real break stays hidden until a customer finds it.
    expect(problem.status).toBe(200);
  });

  it('names the element index when an array member is wrong', async () => {
    server.use(
      http.get('/api/v1/orders/:orderId', () =>
        jsonResponse({
          ...ORDER_RESPONSE_CREATED,
          items: [{ ...ORDER_RESPONSE_CREATED.items[0], unitPrice: 'twenty five' }],
        }),
      ),
    );

    const problem = await problemFrom(
      request(
        { method: 'GET', pathTemplate: '/api/v1/orders/:orderId', params: { orderId: 'x' } },
        orderResponseSchema,
      ),
    );

    expect(problem.detail).toContain('items.0.unitPrice');
  });

  it('raises CONTRACT_VIOLATION when a 200 carries no body', async () => {
    server.use(http.get('/api/v1/ping', () => new HttpResponse(null, { status: 200 })));

    const problem = await problemFrom(
      request({ method: 'GET', pathTemplate: '/api/v1/ping' }, echoSchema),
    );

    expect(problem.code).toBe('CONTRACT_VIOLATION');
    expect(problem.detail).toContain('empty');
  });

  it('raises CONTRACT_VIOLATION when a 200 carries HTML', async () => {
    // What a misconfigured SPA fallback does: serves index.html for /api/v1/... with a 200.
    server.use(
      http.get(
        '/api/v1/ping',
        () =>
          new HttpResponse('<!doctype html><title>app</title>', {
            status: 200,
            headers: { 'Content-Type': CONTENT_TYPE.HTML },
          }),
      ),
    );

    const problem = await problemFrom(
      request({ method: 'GET', pathTemplate: '/api/v1/ping' }, echoSchema),
    );

    expect(problem.code).toBe('CONTRACT_VIOLATION');
    expect(problem.detail).toContain('not JSON');
  });

  it('accepts a 204 with no body through requestNoContent', async () => {
    // Every DELETE in this API answers 204, although all four DOCUMENT 200.
    server.use(http.delete('/api/v1/categories/:id', () => noContentResponse()));

    await expect(
      requestNoContent({
        method: 'DELETE',
        pathTemplate: '/api/v1/categories/:id',
        params: { id: 'x' },
      }),
    ).resolves.toBeUndefined();
  });

  it('still raises the problem when a DELETE fails', async () => {
    server.use(
      http.delete('/api/v1/categories/:id', () =>
        problemResponse(403, PROBLEM_401, CONTENT_TYPE.PROBLEM_ISO),
      ),
    );

    const problem = await problemFrom(
      requestNoContent({
        method: 'DELETE',
        pathTemplate: '/api/v1/categories/:id',
        params: { id: 'x' },
      }),
    );

    expect(problem).toBeInstanceOf(ApiProblem);
    expect(problem.status).toBe(403);
  });

  it('turns a rejected fetch into NETWORK_ERROR carrying the id we sent', async () => {
    server.use(http.get('/api/v1/ping', () => HttpResponse.error()));

    const problem = await problemFrom(
      request({ method: 'GET', pathTemplate: '/api/v1/ping' }, echoSchema),
    );

    expect(problem.code).toBe('NETWORK_ERROR');
    expect(problem.status).toBe(0);
    expect(problem.correlationId).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('aborts on its own deadline and says the request may still have been processed', async () => {
    server.use(
      http.get('/api/v1/ping', async () => {
        await delay(200);
        return jsonResponse({ ok: true });
      }),
    );

    const problem = await problemFrom(
      request({ method: 'GET', pathTemplate: '/api/v1/ping', timeoutMs: 10 }, echoSchema),
    );

    expect(problem.code).toBe('NETWORK_ERROR');
    // The honest sentence. A client timeout does not mean the server did nothing -- which is why
    // the persisted Idempotency-Key exists and why a retry reuses it.
    expect(problem.detail).toContain('may still have been processed');
  });

  /**
   * NOT TESTED HERE, AND THE REASON IS WORTH RECORDING RATHER THAN LEAVING AS A GAP.
   *
   * `fetch` resolves as soon as the HEADERS arrive, so http.ts keeps its deadline armed across
   * `res.text()` -- a response that stalls halfway through its body must time out rather than hang.
   * The behaviour that makes that work is that aborting the signal AFTER the response is returned
   * errors the body stream, and it was verified directly against Node 22's `fetch` with a server
   * that writes a partial chunk and never ends the response: the body read rejects with
   * `DOMException` / `AbortError`, which is exactly what `networkProblem` maps to NETWORK_ERROR.
   *
   * It cannot be asserted through MSW, whose interceptor synthesises the response in-process and
   * does not wire the request's signal to the mocked body stream -- so a test of it here hangs until
   * Vitest's own timeout, which would prove the opposite of what it claimed. Better a named,
   * evidenced omission than a test that is quietly disabled the first time it goes red.
   */

  it("honours the caller's own signal, which is how Query cancels on unmount", async () => {
    server.use(
      http.get('/api/v1/ping', async () => {
        await delay(200);
        return jsonResponse({ ok: true });
      }),
    );

    const controller = new AbortController();
    const pending = problemFrom(
      request(
        { method: 'GET', pathTemplate: '/api/v1/ping', signal: controller.signal },
        echoSchema,
      ),
    );
    const logged = vi.spyOn(console, 'error').mockImplementation(() => {});
    controller.abort();

    expect((await pending).code).toBe('NETWORK_ERROR');
    // A cancellation is not a failure, so it is not logged as one.
    expect(logged).not.toHaveBeenCalled();
  });
});

describe('the request log seam', () => {
  it('records the path TEMPLATE, never the populated path', async () => {
    const records: RequestRecord[] = [];
    const unsubscribe = registerRequestObserver((record) => records.push(record));
    server.use(http.get('/api/v1/orders/:orderId', () => jsonResponse(ORDER_RESPONSE_CREATED)));

    await request(
      {
        method: 'GET',
        pathTemplate: '/api/v1/orders/:orderId',
        params: { orderId: 'b487ec64-c65b-4f29-898e-c82d5d790007' },
      },
      orderResponseSchema,
    );
    unsubscribe();

    expect(records).toHaveLength(1);
    const record = records[0];
    expect(record?.pathTemplate).toBe('/api/v1/orders/:orderId');
    // The ring buffer behind /support is a USER-COPYABLE artifact. An order id on somebody's
    // clipboard and in a ticketing system is not something to ship by accident.
    expect(record?.pathTemplate).not.toContain('b487ec64');
    expect(record?.status).toBe(200);
    expect(record?.method).toBe('GET');
    expect(record?.correlationId).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('records a failure, and records status 0 when there was no response', async () => {
    const records: RequestRecord[] = [];
    const unsubscribe = registerRequestObserver((record) => records.push(record));
    server.use(
      http.get('/api/v1/ping', () => problemResponse(503, PROBLEM_401)),
      http.get('/api/v1/gone', () => HttpResponse.error()),
    );

    await request({ method: 'GET', pathTemplate: '/api/v1/ping' }, echoSchema).catch(() => null);
    await request({ method: 'GET', pathTemplate: '/api/v1/gone' }, echoSchema).catch(() => null);
    unsubscribe();

    expect(records.map((record) => record.status)).toEqual([503, 0]);
  });

  it('stops recording once unsubscribed', async () => {
    const records: RequestRecord[] = [];
    registerRequestObserver((record) => records.push(record))();
    server.use(http.get('/api/v1/ping', () => jsonResponse({ ok: true })));

    await request({ method: 'GET', pathTemplate: '/api/v1/ping' }, echoSchema);

    expect(records).toHaveLength(0);
  });

  it('cannot let a broken observer turn a successful request into a failure', async () => {
    const unsubscribe = registerRequestObserver(() => {
      throw new Error('the support panel is broken');
    });
    server.use(http.get('/api/v1/ping', () => jsonResponse({ ok: true })));

    await expect(
      request({ method: 'GET', pathTemplate: '/api/v1/ping' }, echoSchema),
    ).resolves.toEqual({ ok: true });
    unsubscribe();
  });
});

describe('summariseIssues', () => {
  it('names field paths and caps the list', () => {
    const schema = z.object({ a: z.string(), b: z.string(), c: z.string(), d: z.string() });
    const result = schema.safeParse({});
    expect(result.success).toBe(false);
    if (result.success) {
      return;
    }

    const summary = summariseIssues(result.error);
    expect(summary).toContain('a:');
    // Three, then a count. A wholesale shape change produces dozens and the first three identify
    // it just as well -- and this string ends up in a support surface.
    expect(summary).toContain('(+1 more)');
  });

  it('labels a root-level mismatch rather than printing an empty path', () => {
    const result = z.object({ a: z.string() }).safeParse('not an object at all');
    expect(result.success).toBe(false);
    if (result.success) {
      return;
    }
    expect(summariseIssues(result.error)).toContain('<root>:');
  });
});
