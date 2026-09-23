/**
 * The RFC 7807 parser, against bodies captured from the running gateway.
 *
 * THE HIGHEST-VALUE TEST FILE IN THIS PROJECT, because every one of the cases below is a real
 * response that a plausible parser gets wrong, and the cost of getting it wrong is either a
 * meaningless error on a customer's screen or an unsupportable incident.
 */

import { describe, expect, it } from 'vitest';
import {
  ApiProblem,
  isApiProblem,
  isConflict,
  isIdempotencyKeyConflict,
  isProblemMediaType,
  networkProblem,
  parseProblem,
  readCorrelationId,
  statusToCode,
} from '../problem';
import type { RequestContext } from '../problem';
import {
  CONTENT_TYPE,
  GATEWAY_404_NOT_PROBLEM,
  NGINX_502_HTML,
  PROBLEM_400_VALIDATION,
  PROBLEM_401,
  PROBLEM_403,
  PROBLEM_409_IDEMPOTENCY_DEPLOYED,
  PROBLEM_409_IDEMPOTENCY_HEAD,
  PROBLEM_409_ILLEGAL_TRANSITION,
  PROBLEM_NO_CODE,
} from './fixtures';

const CONTEXT: RequestContext = { method: 'GET', pathTemplate: '/api/v1/orders/:orderId' };
const SENT_ID = 'sent-11111111-2222-3333-4444-555555555555';

function response(
  status: number,
  body: string | null,
  headers: Readonly<Record<string, string>>,
): Response {
  return new Response(body, { status, headers });
}

describe('isProblemMediaType -- startsWith, never ===', () => {
  /**
   * THE REGRESSION THAT MATTERS MOST IN THIS FILE.
   *
   * The build serving localhost:8080 answers a 401 with `;charset=ISO-8859-1` and a 409 with a
   * bare type; HEAD answers the 401 with `;charset=UTF-8` after commit 4aecb3b. An `===`
   * comparison matches one of the three, so every 401 would be routed down the non-problem path
   * -- and the app would lose `UNAUTHORIZED`, the one code that must clear the token and redirect,
   * showing a generic gateway error instead.
   */
  it.each([
    ['bare', CONTENT_TYPE.PROBLEM_BARE],
    ['charset=ISO-8859-1 (the deployed 401)', CONTENT_TYPE.PROBLEM_ISO],
    ['charset=UTF-8 (the 401 at HEAD)', CONTENT_TYPE.PROBLEM_UTF8],
    ['uppercased by a proxy', 'Application/Problem+JSON; charset=utf-8'],
  ])('recognises %s', (_label, contentType) => {
    expect(isProblemMediaType(contentType)).toBe(true);
  });

  it.each([
    ['plain application/json', CONTENT_TYPE.JSON],
    ['an HTML error page', CONTENT_TYPE.HTML],
    ['a type that merely contains the string', 'text/plain; x=application/problem+json'],
  ])('rejects %s', (_label, contentType) => {
    expect(isProblemMediaType(contentType)).toBe(false);
  });

  it('rejects a missing content-type rather than guessing', () => {
    expect(isProblemMediaType(null)).toBe(false);
  });
});

describe('parseProblem -- problem+json bodies', () => {
  it('parses a 401 that arrives with the charset suffix', async () => {
    const problem = await parseProblem(
      response(401, JSON.stringify(PROBLEM_401), { 'Content-Type': CONTENT_TYPE.PROBLEM_ISO }),
      SENT_ID,
      CONTEXT,
    );

    expect(isApiProblem(problem)).toBe(true);
    expect(problem.status).toBe(401);
    expect(problem.code).toBe('UNAUTHORIZED');
    expect(problem.detail).toBe('Authentication is required to access this resource');
    expect(problem.correlationId).toBe(PROBLEM_401.correlationId);
    // `path` and `instance` are kept for console diagnostics and are never rendered or copied.
    expect(problem.path).toBe(PROBLEM_401.path);
    expect(problem.instance).toBe(PROBLEM_401.instance);
    // The path TEMPLATE is what the support surface copies -- no order or user UUID in it.
    expect(problem.pathTemplate).toBe('/api/v1/orders/:orderId');
    expect(problem.method).toBe('GET');
  });

  it('parses a 409 that arrives with a bare content type', async () => {
    const problem = await parseProblem(
      response(409, JSON.stringify(PROBLEM_409_ILLEGAL_TRANSITION), {
        'Content-Type': CONTENT_TYPE.PROBLEM_BARE,
      }),
      SENT_ID,
      CONTEXT,
    );

    expect(problem.code).toBe('CONFLICT');
    expect(isConflict(problem)).toBe(true);
    expect(problem.detail).toBe('Cannot move an order from SHIPMENT_CREATED to CANCELLED');
  });

  it('takes `code` from `error`, never from `message`', async () => {
    // A body whose `error` and `title` disagree, to prove which one wins. docs/security.md commits
    // to `error` as the stable field; `title` is a mirror of it that a future edit could change.
    const problem = await parseProblem(
      response(403, JSON.stringify({ ...PROBLEM_403, title: 'A HUMAN SENTENCE' }), {
        'Content-Type': CONTENT_TYPE.PROBLEM_ISO,
      }),
      SENT_ID,
      CONTEXT,
    );

    expect(problem.code).toBe('FORBIDDEN');
  });

  it('falls back to `title` when `error` is absent', async () => {
    const { error: _error, ...withoutError } = PROBLEM_400_VALIDATION;
    const problem = await parseProblem(
      response(400, JSON.stringify(withoutError), {
        'Content-Type': CONTENT_TYPE.PROBLEM_BARE,
      }),
      SENT_ID,
      CONTEXT,
    );

    expect(problem.code).toBe('VALIDATION_ERROR');
  });

  it('falls back to the status map when neither `error` nor `title` is present', async () => {
    const problem = await parseProblem(
      response(409, JSON.stringify(PROBLEM_NO_CODE), {
        'Content-Type': CONTENT_TYPE.PROBLEM_BARE,
      }),
      SENT_ID,
      CONTEXT,
    );

    expect(problem.code).toBe('CONFLICT');
    expect(problem.detail).toBe('Something conflicted and the body did not say what.');
  });

  it('keeps a problem usable when the body is truncated mid-JSON', async () => {
    // A throw inside the error path erases the failure it was describing. The status and the
    // correlation id still have to survive.
    const problem = await parseProblem(
      response(500, '{"title":"INTERNAL_ERR', { 'Content-Type': CONTENT_TYPE.PROBLEM_BARE }),
      SENT_ID,
      CONTEXT,
    );

    expect(problem.status).toBe(500);
    expect(problem.code).toBe('INTERNAL_ERROR');
    expect(problem.correlationId).toBe(SENT_ID);
    expect(problem.detail).not.toBe('');
  });
});

describe('parseProblem -- the two responses that are NOT problem+json', () => {
  /**
   * The gateway's own 404 for an unrouted path. `application/json`, and it carries an `error`
   * FIELD whose value is the human string "Not Found". A parser that read `body.error` from any
   * JSON response would hand the UI `code: "Not Found"`, which every per-code branch would miss.
   */
  it('does not mistake the gateway 404 for a problem body', async () => {
    const problem = await parseProblem(
      response(404, JSON.stringify(GATEWAY_404_NOT_PROBLEM), {
        'Content-Type': CONTENT_TYPE.JSON,
      }),
      SENT_ID,
      CONTEXT,
    );

    expect(problem.code).toBe('GATEWAY_ERROR');
    expect(problem.code).not.toBe('Not Found');
    expect(problem.status).toBe(404);
    // No `X-Correlation-Id` on this response at all -- verified live. The sent id is the handle.
    expect(problem.correlationId).toBe(SENT_ID);
    // The body is kept for diagnostics, so the support panel can still show what came back.
    expect(problem.raw).toMatchObject({ path: '/nope' });
  });

  it('survives an HTML 502 from nginx', async () => {
    const problem = await parseProblem(
      response(502, NGINX_502_HTML, { 'Content-Type': CONTENT_TYPE.HTML }),
      SENT_ID,
      CONTEXT,
    );

    expect(problem.code).toBe('GATEWAY_ERROR');
    expect(problem.status).toBe(502);
    expect(problem.detail).toContain('Your data is safe');
    expect(problem.raw).toBe(NGINX_502_HTML);
  });

  it('survives a completely empty body', async () => {
    const problem = await parseProblem(response(504, null, {}), SENT_ID, CONTEXT);

    expect(problem.code).toBe('GATEWAY_ERROR');
    expect(problem.status).toBe(504);
    expect(problem.correlationId).toBe(SENT_ID);
  });
});

describe('readCorrelationId -- body first, and the header split', () => {
  it('prefers the body', () => {
    const headers = new Headers({ 'X-Correlation-Id': 'from-header' });
    expect(readCorrelationId({ correlationId: 'from-body' }, headers, SENT_ID)).toBe('from-body');
  });

  /**
   * THE REGRESSION THE BRIEF SINGLES OUT.
   *
   * The deployed gateway emits `X-Correlation-Id` twice -- `CorrelationIdGlobalFilter` sets it and
   * each backend's own filter sets it again, and the routing filter merges both -- so the Fetch
   * spec joins them and `Headers.get()` returns "<id>, <id>". Showing a user
   * "6a767f1d-..., 6a767f1d-..." as their support code is the visible bug; quoting it to support
   * is the expensive one. Commit 4aecb3b fixes the duplication server-side and the split stays,
   * because it costs one string operation and is correct against both builds.
   */
  it('returns exactly one id when the header is duplicated', () => {
    const id = '6a767f1d-d201-4c58-8df6-d6ead2635d2d';
    const headers = new Headers();
    headers.append('X-Correlation-Id', id);
    headers.append('X-Correlation-Id', id);

    // Proof that the joined value really is what a client reads, so this test is about the real
    // mechanism and not about a hand-written string.
    expect(headers.get('X-Correlation-Id')).toBe(`${id}, ${id}`);
    expect(readCorrelationId(null, headers, SENT_ID)).toBe(id);
    expect(readCorrelationId(null, headers, SENT_ID)).not.toContain(',');
  });

  it('falls back to the header when the body has no id', () => {
    const headers = new Headers({ 'X-Correlation-Id': 'from-header' });
    expect(readCorrelationId({ detail: 'no id here' }, headers, SENT_ID)).toBe('from-header');
  });

  it('ignores a blank body id rather than showing an empty support code', () => {
    const headers = new Headers({ 'X-Correlation-Id': 'from-header' });
    expect(readCorrelationId({ correlationId: '   ' }, headers, SENT_ID)).toBe('from-header');
  });

  it('falls back to the id we sent when there is no body and no header', () => {
    // The case that matters: a rejected fetch or a gateway 504. The gateway FORWARDS a
    // client-supplied id rather than minting its own, so the id we generated is already in the
    // server's logs even though we never saw a response.
    expect(readCorrelationId(null, null, SENT_ID)).toBe(SENT_ID);
    expect(readCorrelationId(null, new Headers(), SENT_ID)).toBe(SENT_ID);
  });
});

describe('statusToCode', () => {
  it.each([
    [400, 'VALIDATION_ERROR'],
    [401, 'UNAUTHORIZED'],
    [403, 'FORBIDDEN'],
    [404, 'NOT_FOUND'],
    [405, 'METHOD_NOT_ALLOWED'],
    [409, 'CONFLICT'],
    [500, 'INTERNAL_ERROR'],
    [502, 'GATEWAY_ERROR'],
    [503, 'SERVICE_UNAVAILABLE'],
    [504, 'GATEWAY_ERROR'],
  ])('maps %i to %s', (status, code) => {
    expect(statusToCode(status)).toBe(code);
  });

  it('admits it does not know, rather than inventing a code a branch would act on', () => {
    // A wrong stable code sends the UI down a branch written for a different failure, which is
    // worse than routing to the generic surface. `HTTP_418` flows through the `(string & {})`
    // member of ErrorCode and matches no per-code branch.
    expect(statusToCode(418)).toBe('HTTP_418');
  });
});

describe('the 409 disambiguation', () => {
  /**
   * A 409 on order creation is disambiguated BY CALL SITE, never by code, and these two fixtures
   * are why: the same situation carries `CONFLICT` on the deployed build and
   * `IDEMPOTENCY_KEY_CONFLICT` at HEAD. A client that tested for either one alone would be wrong
   * against the other.
   */
  it('reports a 409 as a conflict on both builds', async () => {
    for (const body of [PROBLEM_409_IDEMPOTENCY_DEPLOYED, PROBLEM_409_IDEMPOTENCY_HEAD]) {
      const problem = await parseProblem(
        response(409, JSON.stringify(body), { 'Content-Type': CONTENT_TYPE.PROBLEM_BARE }),
        SENT_ID,
        { method: 'POST', pathTemplate: '/api/v1/orders' },
      );
      expect(isConflict(problem)).toBe(true);
    }
  });

  it('only claims a key conflict when the server said so explicitly', async () => {
    const deployed = await parseProblem(
      response(409, JSON.stringify(PROBLEM_409_IDEMPOTENCY_DEPLOYED), {
        'Content-Type': CONTENT_TYPE.PROBLEM_BARE,
      }),
      SENT_ID,
      { method: 'POST', pathTemplate: '/api/v1/orders' },
    );
    const head = await parseProblem(
      response(409, JSON.stringify(PROBLEM_409_IDEMPOTENCY_HEAD), {
        'Content-Type': CONTENT_TYPE.PROBLEM_BARE,
      }),
      SENT_ID,
      { method: 'POST', pathTemplate: '/api/v1/orders' },
    );

    expect(isIdempotencyKeyConflict(deployed)).toBe(false);
    expect(isIdempotencyKeyConflict(head)).toBe(true);
  });
});

describe('networkProblem', () => {
  it('reports a rejected fetch with status 0, not a fabricated 500', () => {
    const problem = networkProblem(new TypeError('Failed to fetch'), SENT_ID, CONTEXT);

    expect(problem.code).toBe('NETWORK_ERROR');
    // 0 is the honest value: calling it a 500 would route it to "the server had a problem" when
    // the server may never have been reached at all.
    expect(problem.status).toBe(0);
    expect(problem.correlationId).toBe(SENT_ID);
    expect(problem.detail).toContain('could not reach');
  });

  it('gives an abort different words from an offline failure, and the same code', () => {
    const problem = networkProblem(
      new DOMException('Request timed out', 'AbortError'),
      SENT_ID,
      CONTEXT,
    );

    // The same code, deliberately: the brief fixes three synthesised codes, and adding a fourth
    // would leave a hole in every feature stage's per-code table. A user who waited fifteen
    // seconds needs different WORDS, and words are what `detail` is for.
    expect(problem.code).toBe('NETWORK_ERROR');
    expect(problem.detail).toContain('15 seconds');
    expect(problem.detail).toContain('may still have been processed');
  });
});

describe('ApiProblem', () => {
  it('is an Error, so an uncaught one still says something in a console', () => {
    const problem = new ApiProblem({
      status: 409,
      code: 'CONFLICT',
      detail: 'the detail',
      correlationId: SENT_ID,
      method: 'POST',
      pathTemplate: '/api/v1/orders',
    });

    expect(problem).toBeInstanceOf(Error);
    expect(problem.name).toBe('ApiProblem');
    expect(problem.message).toBe('the detail');
  });

  it('is not confused with a plain Error by `isApiProblem`', () => {
    expect(isApiProblem(new Error('nope'))).toBe(false);
    expect(isApiProblem(null)).toBe(false);
    expect(isApiProblem({ status: 409, code: 'CONFLICT' })).toBe(false);
  });
});
