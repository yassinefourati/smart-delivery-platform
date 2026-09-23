/**
 * THE ONLY `fetch` IN THIS APPLICATION.
 *
 * Everything the platform is difficult about is implemented here, once: the relative base, the
 * bearer token, the outbound `X-Correlation-Id`, the `Idempotency-Key`, the `startsWith`
 * content-type check, the duplicated correlation header, the abort timeout and boundary
 * validation. A second `fetch()` anywhere silently opts out of all of it, and the symptom is a
 * support request with no correlation id attached to it -- which is why an ESLint
 * `no-restricted-globals` rule makes `fetch`, `window.fetch`, `globalThis.fetch` and
 * `XMLHttpRequest` errors everywhere except this directory, and `axios` an error everywhere.
 *
 * EVERY URL THIS MODULE BUILDS IS A RELATIVE PATH. `API_BASE_PATH` is `''` in every shipped
 * environment and `src/config.ts` rejects any value carrying a scheme or an authority, so there
 * is no way to express an absolute origin from configuration and no way to write one in source
 * (a second ESLint rule bans `http://localhost:8` literals inside `src/`). The browser is
 * therefore SAME-ORIGIN with the API in both environments -- Vite's `server.proxy` in dev, one
 * nginx container in production -- so no CORS preflight is ever issued. That matters because
 * the gateway rejects preflights before routing: `OPTIONS /api/v1/products` with an `Origin`
 * returns a bodyless 403 plus the `Vary: Origin / Access-Control-Request-Method /
 * Access-Control-Request-Headers` triple, which a browser reports as an opaque network error
 * with nothing in it to debug.
 *
 * WHAT THIS MODULE NEVER LOGS. It attaches `Authorization: Bearer <token>` and it sends login
 * bodies. So `console.error` here takes method, path TEMPLATE, status, stable code and
 * correlation id -- and nothing else. Never a body, never a headers object, never the `init`.
 * A screenshot of a console in a support ticket makes whatever was in it permanent. This is
 * lint-enforced as well as stated.
 */

import { z } from 'zod';
import { API_BASE_PATH } from '../../config';
import { REQUEST_TIMEOUT_MS, contractViolation, networkProblem, parseProblem } from './problem';
import type { RequestContext } from './problem';

/* -------------------------------------------------------------------------------------- */
/* Seam 1: the bearer token                                                                */
/* -------------------------------------------------------------------------------------- */

/**
 * How the access token reaches a request, WITHOUT this module ever holding it.
 *
 * `src/lib/auth/tokenStore.ts` keeps the token in a module-scoped closure variable -- not
 * localStorage, not sessionStorage, not a cookie, not React state -- and exposes NO getter to
 * anything. It registers an `attach` function here at module load, and that function mutates a
 * `Headers` object in place. So the token value crosses no module boundary at all: this file
 * cannot read it even if a future edit tried to log it.
 *
 * WHY MEMORY-ONLY. docs/security.md records that this platform has NO TOKEN REVOCATION --
 * "nothing can invalidate an issued access token before it expires... Logout, a password
 * change, or a role change all take up to one token lifetime to take effect". A token lifted
 * out of web storage is a valid credential for up to sixty minutes, replayable from any
 * machine, and there is no button anywhere in this platform that kills it. Memory-only does not
 * stop XSS -- an injected script can wrap `fetch` and read this header on its way out -- but it
 * removes exfiltration that OUTLIVES THE TAB, which is the only impact reduction a client can
 * make when revocation does not exist.
 *
 * UNTIL tokenStore registers itself, requests carry no `Authorization` header. That is the
 * right default: a protected call fails closed with a 401 the app knows how to handle, rather
 * than appearing to work.
 */
export type AuthorizationAttacher = (headers: Headers) => void;

let authorizationAttacher: AuthorizationAttacher | null = null;

/** Called once, by `src/lib/auth/tokenStore.ts`. Pass `null` to detach (tests, logout). */
export function setAuthorizationAttacher(attach: AuthorizationAttacher | null): void {
  authorizationAttacher = attach;
}

/* -------------------------------------------------------------------------------------- */
/* Seam 2: the request log                                                                 */
/* -------------------------------------------------------------------------------------- */

/**
 * One line per request, for the support surface.
 *
 * `src/lib/support/correlationLog.ts` keeps the last twenty of these in an in-memory ring
 * buffer and `/support` renders them with a Copy report button. It registers itself here
 * rather than being imported by this file, because the dependency runs the other way: the HTTP
 * layer must not need to know that a support screen exists.
 *
 * `pathTemplate`, NOT the populated path. The ring buffer is a USER-COPYABLE artifact:
 * `/api/v1/orders/:id` tells support exactly what they need without putting order and user
 * UUIDs onto somebody's clipboard and into a ticketing system.
 */
export interface RequestRecord {
  readonly method: string;
  readonly pathTemplate: string;
  /** 0 when the request never got a response (a rejected fetch or our own abort). */
  readonly status: number;
  readonly correlationId: string;
  readonly at: number;
}

export type RequestObserver = (record: RequestRecord) => void;

const observers = new Set<RequestObserver>();

/** Register an observer. Returns its unsubscribe function. */
export function registerRequestObserver(observer: RequestObserver): () => void {
  observers.add(observer);
  return () => {
    observers.delete(observer);
  };
}

function notify(record: RequestRecord): void {
  for (const observer of observers) {
    try {
      observer(record);
    } catch {
      // An observer must never be able to turn a successful request into a failure. There is
      // deliberately nothing logged here: the only thing worth saying is already in `record`,
      // and a broken support log is not worth a console line on every request.
    }
  }
}

/* -------------------------------------------------------------------------------------- */
/* Request description                                                                     */
/* -------------------------------------------------------------------------------------- */

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE';

/** A query value. `undefined` members are OMITTED, which is not the same as sending empty. */
export type QueryValue = string | number | boolean | undefined;

/**
 * A request, described rather than built.
 *
 * `pathTemplate` plus `params` rather than a finished path, ON PURPOSE. The template is needed
 * for the log and for the error, and deriving the populated path FROM it means the two can
 * never drift -- there is no second place to update when a route changes, and no chance of
 * logging `/api/v1/orders/:id` while actually calling `/api/v1/order/:id`. Path segments are
 * `encodeURIComponent`-ed, so an id containing a slash cannot escape its segment.
 */
export interface ApiRequest {
  readonly method: HttpMethod;
  /** e.g. `/api/v1/orders/:id/status`. Leading slash, no origin, no query. */
  readonly pathTemplate: string;
  /** Values for the `:name` segments. Every segment in the template must have one. */
  readonly params?: Readonly<Record<string, string>> | undefined;
  readonly query?: Readonly<Record<string, QueryValue>> | undefined;
  /** Serialised with `JSON.stringify`. Omit for GET and DELETE. */
  readonly body?: unknown;
  /**
   * The literal `Idempotency-Key` header value. Only `POST /api/v1/orders` accepts one.
   *
   * THE KEY MUST BE OLDER THAN THE CLICK -- it is minted when `/checkout` mounts with a
   * non-empty cart and persisted in the cart blob, never minted in the click handler. Two
   * clicks producing two keys IS the duplicate-order bug. See src/domain/idempotency.ts for
   * the lifecycle and the rotate/keep matrix.
   */
  readonly idempotencyKey?: string | undefined;
  /** TanStack Query passes one per query; it aborts on unmount and on key change. */
  readonly signal?: AbortSignal | undefined;
  readonly timeoutMs?: number | undefined;
}

/* -------------------------------------------------------------------------------------- */
/* The two entry points                                                                    */
/* -------------------------------------------------------------------------------------- */

/**
 * Perform a request and PARSE its body through `schema`.
 *
 * Throws `ApiProblem` and nothing else, for every failure: a non-2xx response, a rejected
 * fetch, a body that is not JSON, or a body that does not match the schema.
 */
export async function request<TSchema extends z.ZodType>(
  spec: ApiRequest,
  schema: TSchema,
): Promise<z.output<TSchema>> {
  const { res, context, correlationId, dispose } = await send(spec);

  /**
   * THE DEADLINE STAYS OPEN UNTIL THE BODY IS IN HAND, which is why `dispose` is returned from
   * `send` instead of being cleared there.
   *
   * `fetch` resolves as soon as the HEADERS arrive; the body is still a stream. Clearing the timer
   * at that moment means a response that stalls halfway through its body hangs forever, and "the
   * request timed out" is precisely the thing this module promised to be able to say.
   *
   * Aborting the signal AFTER `fetch` has resolved does error the body stream -- verified directly
   * against Node 22's `fetch` with a server that writes a partial chunk and never ends the
   * response: `res.text()` rejects with `DOMException` / `AbortError`, which `networkProblem` maps
   * to NETWORK_ERROR with the timeout wording. So leaving the timer armed across `res.text()` makes
   * the 15 seconds cover the whole exchange rather than just its first packet. The note in
   * __tests__/http.test.ts records why this is not asserted by a test.
   */
  let text: string;
  try {
    text = await res.text();
  } catch (cause) {
    // An aborted or broken body stream. It is not a contract violation -- the server may have said
    // something perfectly valid that we never received -- so it is reported as what it is.
    const problem = networkProblem(cause, correlationId, context);
    console.error(
      '[api] %s %s -> body not received (%s) support=%s',
      context.method,
      context.pathTemplate,
      problem.code,
      correlationId,
    );
    throw problem;
  } finally {
    dispose();
  }

  if (text.trim() === '') {
    // A 2xx with no body where one was promised. This is a contract failure, not an empty
    // result: callers that legitimately expect no body use `requestNoContent` below.
    throw contractViolation('the response body was empty', res.status, correlationId, context, '');
  }

  let json: unknown;
  try {
    json = JSON.parse(text) as unknown;
  } catch {
    throw contractViolation(
      'the response body was not JSON',
      res.status,
      correlationId,
      context,
      text,
    );
  }

  const parsed = schema.safeParse(json);
  if (!parsed.success) {
    const summary = summariseIssues(parsed.error);
    // Method, path TEMPLATE, status, code and correlation id. Never the body -- and the body is
    // exactly what a well-meaning person would add here to "make it debuggable", which is how a
    // user's address or a product catalogue ends up in a support screenshot. The zod summary
    // carries the field PATHS, which is the part that identifies the break.
    console.error(
      '[api] contract violation: %s %s -> %d (%s) support=%s',
      context.method,
      context.pathTemplate,
      res.status,
      summary,
      correlationId,
    );
    throw contractViolation(summary, res.status, correlationId, context, json);
  }

  return parsed.data;
}

/**
 * Perform a request that legitimately returns no body.
 *
 * Every DELETE in this API answers 204 with an empty body, although all four of them DOCUMENT
 * 200 -- verified live on `DELETE /api/v1/categories/{id}`. A caller written from the OpenAPI
 * document would either expect a body that is not there or treat 204 as unexpected.
 */
export async function requestNoContent(spec: ApiRequest): Promise<void> {
  const { res, dispose } = await send(spec);
  try {
    // Drain the body even when we do not want it: an unread body on some runtimes keeps the
    // connection from being reused. Failure to drain is not a failure of the request -- the status
    // was 2xx and the caller asked for nothing else.
    await res.text().catch(() => '');
  } finally {
    dispose();
  }
}

/* -------------------------------------------------------------------------------------- */
/* The shared middle                                                                       */
/* -------------------------------------------------------------------------------------- */

interface SendResult {
  readonly res: Response;
  readonly context: RequestContext;
  readonly correlationId: string;
  /**
   * Clears the deadline. THE CALLER MUST CALL IT, in a `finally`, once the body is read.
   *
   * Returned rather than called inside `send` so that the timeout covers the body stream and not
   * just the headers -- see the comment in `request`. On every failure path inside `send` it has
   * already been called.
   */
  readonly dispose: () => void;
}

async function send(spec: ApiRequest): Promise<SendResult> {
  const context: RequestContext = { method: spec.method, pathTemplate: spec.pathTemplate };

  /**
   * OUTBOUND, ON EVERY REQUEST, BEFORE THERE IS A RESPONSE TO READ ONE FROM.
   *
   * `CorrelationIdGlobalFilter` FORWARDS a client-supplied `X-Correlation-Id` rather than
   * minting its own, so sending one means the id that joins this call to the server's log lines
   * exists before the call does. That is the entire difference between "something went wrong"
   * and a supportable incident on a gateway 504, on an nginx 502, or on a rejected fetch --
   * every case where there is no response body and no header to read an id back out of.
   */
  const correlationId = crypto.randomUUID();

  const url = buildUrl(spec);
  const headers = new Headers({
    // Both, because both are real: a success is `application/json` and a failure is
    // `application/problem+json`.
    Accept: 'application/json, application/problem+json',
    'X-Correlation-Id': correlationId,
  });

  if (spec.body !== undefined) {
    headers.set('Content-Type', 'application/json');
  }
  if (spec.idempotencyKey !== undefined) {
    headers.set('Idempotency-Key', spec.idempotencyKey);
  }
  // Last, so nothing above can be made to overwrite it, and so this file touches the token
  // through exactly one call.
  authorizationAttacher?.(headers);

  const timeout = createTimeout(spec.signal, spec.timeoutMs ?? REQUEST_TIMEOUT_MS);

  let res: Response;
  try {
    res = await fetch(url, {
      method: spec.method,
      headers,
      body: spec.body === undefined ? null : JSON.stringify(spec.body),
      signal: timeout.signal,
      // No cookies are involved anywhere in this platform's auth, so there is nothing to send
      // and nothing a same-site rule has to be reasoned about. Stated rather than defaulted.
      credentials: 'omit',
    });
  } catch (cause) {
    timeout.dispose();
    notify({ ...context, status: 0, correlationId, at: Date.now() });
    const problem = networkProblem(cause, correlationId, context);
    // A caller-initiated abort is Query cancelling a request it no longer wants (unmount, or a
    // query key that changed mid-flight). Nothing failed, so nothing is logged as an error --
    // otherwise every keystroke in a search box leaves a red line in the console.
    if (spec.signal?.aborted !== true) {
      console.error(
        '[api] %s %s -> no response (%s) support=%s',
        context.method,
        context.pathTemplate,
        problem.code,
        correlationId,
      );
    }
    throw problem;
  }

  // Recorded for SUCCESS as well as failure. A support report that shows only the failures
  // hides the sequence that led to them.
  notify({ ...context, status: res.status, correlationId, at: Date.now() });

  if (!res.ok) {
    // The error body is read here, still inside the deadline, and the timer is cleared immediately
    // afterwards -- an error response that stalls mid-body must not hang either.
    const problem = await parseProblem(res, correlationId, context);
    timeout.dispose();
    console.error(
      '[api] %s %s -> %d %s support=%s',
      context.method,
      context.pathTemplate,
      problem.status,
      problem.code,
      problem.correlationId,
    );
    throw problem;
  }

  return { res, context, correlationId, dispose: timeout.dispose };
}

/* -------------------------------------------------------------------------------------- */
/* URL building                                                                            */
/* -------------------------------------------------------------------------------------- */

/**
 * Fill a template's `:name` segments and append the query.
 *
 * Exported for its own test: this is the function that decides what a request actually asks
 * for, and getting a segment wrong is a 404 that looks like missing data.
 *
 * THE RESULT IS A RELATIVE PATH, WITH NO ORIGIN, and that is the point -- but it has one
 * consequence worth knowing before it costs somebody an afternoon. A real browser resolves a
 * relative `fetch` against the document; Node's `undici` does NOT, and jsdom's `location` is not a
 * base for it. So under Vitest this works through MSW (whose interceptor resolves against
 * `location.href`) and fails with a bare `NETWORK_ERROR` in a test that bypasses MSW and expects to
 * reach a real server. That is a property of the test environment, not of this function: a script
 * that genuinely needs to drive the live gateway from Node should use `scripts/verify-live-api.mjs`,
 * which builds absolute urls from a `--base`, rather than reaching through this module.
 */
export function buildUrl(spec: Pick<ApiRequest, 'pathTemplate' | 'params' | 'query'>): string {
  const path = expandPathTemplate(spec.pathTemplate, spec.params);
  const search = buildSearch(spec.query);
  return `${API_BASE_PATH}${path}${search}`;
}

export function expandPathTemplate(
  template: string,
  params: Readonly<Record<string, string>> | undefined,
): string {
  return template.replace(/:([A-Za-z][A-Za-z0-9_]*)/g, (_match, name: string) => {
    const value = params?.[name];
    if (value === undefined) {
      // A programming error, not a server error, so it is not an `ApiProblem`: no request was
      // made and there is nothing for a user to quote. Failing here beats calling
      // `/api/v1/orders/undefined` and reading a 404 as "your order does not exist".
      throw new Error(`Missing path parameter ":${name}" for ${template}`);
    }
    return encodeURIComponent(value);
  });
}

function buildSearch(query: Readonly<Record<string, QueryValue>> | undefined): string {
  if (query === undefined) {
    return '';
  }
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    // `undefined` means "not set", and an absent parameter is what the server treats as a
    // default. Sending `?minPrice=` instead would be a value, and Spring would try to bind it.
    if (value === undefined) {
      continue;
    }
    search.append(key, String(value));
  }
  const rendered = search.toString();
  return rendered === '' ? '' : `?${rendered}`;
}

/* -------------------------------------------------------------------------------------- */
/* Timeout                                                                                 */
/* -------------------------------------------------------------------------------------- */

interface Timeout {
  readonly signal: AbortSignal;
  readonly dispose: () => void;
}

/**
 * Combine the caller's signal with our own deadline.
 *
 * Written by hand rather than with `AbortSignal.any` and `AbortSignal.timeout`: both are recent
 * statics, and jsdom's `AbortSignal` is jsdom's own -- a helper that is present in the browser
 * and absent in the test environment fails in the place least likely to be noticed. Twelve
 * explicit lines cost less than that class of surprise.
 */
function createTimeout(callerSignal: AbortSignal | undefined, timeoutMs: number): Timeout {
  const controller = new AbortController();

  const abortFromCaller = (): void => {
    controller.abort(callerSignal?.reason);
  };

  if (callerSignal !== undefined) {
    if (callerSignal.aborted) {
      abortFromCaller();
    } else {
      callerSignal.addEventListener('abort', abortFromCaller, { once: true });
    }
  }

  const timer = setTimeout(() => {
    controller.abort(new DOMException('Request timed out', 'AbortError'));
  }, timeoutMs);

  return {
    signal: controller.signal,
    dispose: () => {
      clearTimeout(timer);
      callerSignal?.removeEventListener('abort', abortFromCaller);
    },
  };
}

/* -------------------------------------------------------------------------------------- */
/* Diagnostics                                                                             */
/* -------------------------------------------------------------------------------------- */

/**
 * Turn zod issues into one line naming the FIELD PATHS.
 *
 * The paths are the whole value of validating at the boundary: `content.0.createdAt: expected
 * string, received null` says which field, in which element, of which response -- instead of
 * `undefined` appearing at render time in a component three levels away from the fetch. Capped
 * at three issues because a wholesale shape change produces dozens and the first three identify
 * it just as well.
 */
export function summariseIssues(error: z.ZodError): string {
  const shown = error.issues.slice(0, 3).map((issue) => {
    const path = issue.path.length === 0 ? '<root>' : issue.path.map(String).join('.');
    return `${path}: ${issue.message}`;
  });
  const extra = error.issues.length - shown.length;
  return extra > 0 ? `${shown.join('; ')} (+${String(extra)} more)` : shown.join('; ');
}
