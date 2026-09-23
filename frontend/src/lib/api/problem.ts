/**
 * THE ONE ERROR TYPE IN THIS APPLICATION.
 *
 * Every failure a screen can see -- a validated 400, a 401 from the security filter chain, a
 * 403 from an ownership predicate, a 409 from the saga, a 502 from nginx, the gateway's own 504
 * from `response-timeout: 5s`, an HTML error page, an empty body, a rejected `fetch`, a
 * response whose shape no longer matches its schema -- arrives here as an `ApiProblem`. So
 * `catch (e) { if (e instanceof ApiProblem) ... }` is the only error idiom in the codebase, and
 * there is no second shape for a caller to remember or forget.
 *
 * THE RULE ABOUT BRANCHING, and it is the whole reason this file exists:
 *
 *   BRANCH ON `code`. NEVER ON `detail`.
 *
 * docs/security.md states it directly -- `error` (mirrored into RFC 7807's `title`) "is stable
 * and safe to branch on; `message` is for humans and may be reworded". A parser over `detail`
 * is a silent breakage waiting for a copy edit. `detail` is display text: rendered, never
 * matched, never switched on, never split into a per-field error map. (VALIDATION_ERROR's
 * detail really is `"quantity: must be greater than 0; email: must be a well-formed email
 * address"`, and parsing that format is tempting and wrong -- it is not contractual. The honest
 * cost is that a server-only rule shows as a form-level banner rather than against its field.)
 *
 * FOUR PARSING DETAILS, EACH VERIFIED AGAINST THE RUNNING GATEWAY, EACH OF WHICH IS A BUG IF
 * YOU GET IT WRONG. They are implemented and commented individually below:
 *   1. the media-type test is `startsWith`, never `===`;
 *   2. `correlationId` is read from the BODY first, and the header fallback is split on `,`;
 *   3. `code` comes from `error`, then `title`, then a status map -- never from `message`;
 *   4. a JSON body that is NOT problem+json is not a problem body, however much it looks like
 *      one.
 */

import { problemDetailSchema } from './schemas/common';
import type { ProblemDetailBody } from './schemas/common';

/* -------------------------------------------------------------------------------------- */
/* Codes                                                                                   */
/* -------------------------------------------------------------------------------------- */

/**
 * The stable codes this platform emits, transcribed from the table in
 * docs/security.md#error-codes. THIRTEEN, and the one to read the note on is the last.
 */
export const SERVER_ERROR_CODES = [
  'VALIDATION_ERROR',
  'MALFORMED_REQUEST',
  'INVALID_PRODUCT',
  'UNAUTHORIZED',
  'FORBIDDEN',
  'NOT_FOUND',
  'METHOD_NOT_ALLOWED',
  'CONFLICT',
  'EMAIL_ALREADY_EXISTS',
  'CONCURRENT_MODIFICATION',
  'SERVICE_UNAVAILABLE',
  'INTERNAL_ERROR',
  /**
   * DOCUMENTED, AND NOT EMITTED BY THE BUILD RUNNING RIGHT NOW. Read this before branching
   * on it.
   *
   * docs/order-flow.md:136 has promised `409 IDEMPOTENCY_KEY_CONFLICT` since Phase 7, and HEAD
   * of this repository now delivers it: commit 4aecb3b gave
   * `IdempotencyKeyConflictException` its own handler in order-service's
   * `GlobalExceptionHandler`. But the container serving http://localhost:8080 predates that
   * commit, and Docker is unavailable here to rebuild it -- so a same-key-different-body POST
   * verified TODAY answers 409 with `error: "CONFLICT"`, not this code.
   *
   * Both builds are therefore in play, and the client is written to be right on either:
   *   - it NEVER uses the presence of this code as the way to detect the situation;
   *   - it disambiguates a 409 BY WHICH MUTATION WAS ATTEMPTED (see `isConflict` below and the
   *     comment on `createOrder` in endpoints.ts);
   *   - and when this code IS present it is strictly extra information -- see
   *     `isIdempotencyKeyConflict`.
   * A client that branched on the documented code alone would have been broken against the
   * deployed platform; one that branched on `CONFLICT` alone will be broken by the next
   * deploy. Neither happens here.
   */
  'IDEMPOTENCY_KEY_CONFLICT',
] as const;

export type ServerErrorCode = (typeof SERVER_ERROR_CODES)[number];

/**
 * Codes this CLIENT synthesises, for failures that never reached a `@RestControllerAdvice` and
 * so have no stable code of their own.
 *
 * `NETWORK_ERROR`  -- `fetch` itself rejected: offline, DNS, a connection reset, or our own
 *                     15-second abort. There is no response, so there is no status and no
 *                     body; the correlation id we SENT is the only handle that exists, which
 *                     is exactly why http.ts mints one outbound on every request.
 * `GATEWAY_ERROR`  -- a response arrived and it is not problem+json: an nginx 502, the
 *                     gateway's 504, an HTML error page, an empty body, or the gateway's own
 *                     `application/json` 404 for an unrouted path.
 * `CONTRACT_VIOLATION` -- the response parsed as JSON and then failed its schema. A renamed or
 *                     dropped field surfaces HERE, with a field path, instead of as
 *                     `undefined` at render time three components away.
 */
export const CLIENT_ERROR_CODES = ['NETWORK_ERROR', 'GATEWAY_ERROR', 'CONTRACT_VIOLATION'] as const;

export type ClientErrorCode = (typeof CLIENT_ERROR_CODES)[number];

/**
 * `code`'s type.
 *
 * THE `(string & {})` MEMBER IS DELIBERATE and it is the difference between a forward-compatible
 * client and one that throws inside its own error handler. It keeps autocomplete for the
 * sixteen known codes while making an unrecognised FUTURE code representable, so a new server
 * code routes to the generic surface instead of failing to be assigned to a narrower type.
 * This platform's compatibility rule permits adding codes; a closed union here would make a
 * permitted server change a client crash.
 */
export type ErrorCode = ServerErrorCode | ClientErrorCode | (string & Record<never, never>);

/** The media type RFC 7807 responses carry. Compared with `startsWith`. Always. */
export const PROBLEM_MEDIA_TYPE = 'application/problem+json';

/**
 * How long http.ts waits before aborting. Declared here because the abort's `detail` text is
 * written here and the two must agree.
 *
 * FIFTEEN SECONDS, AND IT IS DELIBERATELY LONGER THAN THE GATEWAY'S OWN BUDGET
 * (`connect-timeout: 3000` plus `response-timeout: 5s`). A client timeout SHORTER than the
 * server's turns a request the gateway is about to answer into a client-side failure -- and
 * then a retry of a write whose first attempt may already have landed. The whole point of the
 * `Idempotency-Key` design is not to be in that position.
 */
export const REQUEST_TIMEOUT_MS = 15_000;

/**
 * Status -> code, for the one case that reaches it: a problem+json body carrying NEITHER
 * `error` NOR `title`. Every response this platform produces today carries both, so this is
 * defence against a future or third-party problem body rather than a path in normal use.
 *
 * Unmapped statuses become `HTTP_<status>`, which flows through the `(string & {})` member to
 * the generic surface. Inventing `CONFLICT` for, say, a 412 would be worse than admitting we
 * do not know: a wrong stable code sends the UI down a branch written for a different failure.
 */
export function statusToCode(status: number): ErrorCode {
  switch (status) {
    case 400:
      return 'VALIDATION_ERROR';
    case 401:
      return 'UNAUTHORIZED';
    case 403:
      return 'FORBIDDEN';
    case 404:
      return 'NOT_FOUND';
    case 405:
      return 'METHOD_NOT_ALLOWED';
    case 409:
      return 'CONFLICT';
    case 500:
      return 'INTERNAL_ERROR';
    case 502:
    case 504:
      return 'GATEWAY_ERROR';
    case 503:
      return 'SERVICE_UNAVAILABLE';
    default:
      return `HTTP_${String(status)}`;
  }
}

/* -------------------------------------------------------------------------------------- */
/* The error                                                                               */
/* -------------------------------------------------------------------------------------- */

/** What the caller knows about the request, so a problem can name itself without the URL. */
export interface RequestContext {
  readonly method: string;
  /**
   * The path TEMPLATE -- `/api/v1/orders/:id`, never `/api/v1/orders/8fc9c485-...`.
   *
   * Carried on the error because `<CorrelationRef>`'s copy button puts the support code plus
   * the status, the code and the path template on the clipboard, and the ring buffer behind
   * `/support` is a USER-COPYABLE artifact. The template tells support what they need without
   * putting order and user UUIDs into a paste. `path` and `instance` below hold the populated
   * path for `console.error`; they are never rendered and never copied.
   */
  readonly pathTemplate: string;
}

export interface ApiProblemInit extends RequestContext {
  readonly status: number;
  readonly code: ErrorCode;
  readonly detail: string;
  readonly instance?: string | undefined;
  readonly path?: string | undefined;
  readonly correlationId: string;
  readonly raw?: unknown;
}

/**
 * The application's single error.
 *
 * `status` is 0 when there was no response at all (`NETWORK_ERROR`), which is the honest value:
 * pretending a rejected fetch was a 500 would route it to "the server had a problem" when the
 * server may never have been reached.
 */
export class ApiProblem extends Error {
  readonly status: number;
  readonly code: ErrorCode;
  /** Human-readable text. Rendered as secondary copy. NEVER matched, split or switched on. */
  readonly detail: string;
  readonly instance: string | undefined;
  readonly path: string | undefined;
  readonly correlationId: string;
  readonly method: string;
  readonly pathTemplate: string;
  /** The parsed body, or the raw text when it was not JSON. For diagnostics only. */
  readonly raw: unknown;

  constructor(init: ApiProblemInit) {
    // `message` carries the same text as `detail` so that an uncaught ApiProblem in a browser
    // console still says something useful. Screens read `detail`; nothing reads `message`.
    super(init.detail);
    this.name = 'ApiProblem';
    this.status = init.status;
    this.code = init.code;
    this.detail = init.detail;
    this.instance = init.instance;
    this.path = init.path;
    this.correlationId = init.correlationId;
    this.method = init.method;
    this.pathTemplate = init.pathTemplate;
    this.raw = init.raw;
  }
}

export function isApiProblem(value: unknown): value is ApiProblem {
  return value instanceof ApiProblem;
}

/** A 409 of any kind. See the note on `IDEMPOTENCY_KEY_CONFLICT` for why this is status-based. */
export function isConflict(problem: ApiProblem): boolean {
  return problem.status === 409;
}

/**
 * True only when the server explicitly said the key was reused with a different body.
 *
 * USE THIS AS EXTRA INFORMATION, NEVER AS THE TEST. It is `false` against the currently
 * deployed order-service, which answers that exact situation with `CONFLICT` -- so a checkout
 * screen that showed "you changed your cart" only when this returns true would say nothing at
 * all today. The test is "a 409 came back from POST /api/v1/orders"; this function only tells
 * you whether the server was able to be specific about why.
 */
export function isIdempotencyKeyConflict(problem: ApiProblem): boolean {
  return problem.status === 409 && problem.code === 'IDEMPOTENCY_KEY_CONFLICT';
}

/* -------------------------------------------------------------------------------------- */
/* Parsing                                                                                 */
/* -------------------------------------------------------------------------------------- */

/**
 * DETAIL 1 OF 4: the media-type test.
 *
 * `startsWith`, lowercased, NEVER `===`. Verified live on the platform serving localhost:8080
 * right now: a 401 from the security filter chain arrives as
 * `application/problem+json;charset=ISO-8859-1` while a 409 from a `@RestControllerAdvice`
 * arrives as bare `application/problem+json`. An equality check silently routes EVERY 401 down
 * the non-problem path, so the app loses `UNAUTHORIZED` -- the one code that must clear the
 * token and redirect -- and shows a generic gateway error instead. (Commit 4aecb3b changes the
 * charset to UTF-8 at HEAD, which is a third spelling of the same header and the third reason
 * not to compare it for equality.)
 */
export function isProblemMediaType(contentType: string | null): boolean {
  return contentType !== null && contentType.toLowerCase().startsWith(PROBLEM_MEDIA_TYPE);
}

/**
 * DETAIL 2 OF 4: the correlation id.
 *
 * BODY FIRST. `X-Correlation-Id` is emitted TWICE on every gateway response by the build
 * running right now -- `CorrelationIdGlobalFilter` sets it and each backend's own
 * `CorrelationIdFilter` sets it again, and the routing filter merges both -- so a single
 * `Headers.get()` returns `"<id>, <id>"` because the Fetch spec joins duplicate field values
 * with a comma. Verified on a 200 and on a 401. Showing a user `"6a767f1d-..., 6a767f1d-..."`
 * as their support code is the visible bug; quoting it to support is the expensive one.
 *
 * Commit 4aecb3b fixes the duplication server-side (the gateway now sets the header on
 * `beforeCommit`, after the merge). The split stays anyway: it costs one string operation, it
 * is correct against both builds, and it is correct against any future proxy that merges
 * headers. A defensive split is cheaper than a support ticket.
 *
 * THE LAST FALLBACK IS THE ID WE SENT, and it is the important one: on a rejected fetch or a
 * gateway 504 there is no body and often no header, yet `CorrelationIdGlobalFilter` FORWARDS a
 * client-supplied id rather than minting its own -- so the id we generated before the request
 * is already in the server's logs. That is the difference between "something went wrong" and a
 * supportable incident.
 */
export function readCorrelationId(
  body: ProblemDetailBody | null,
  headers: Headers | null,
  sentCorrelationId: string,
): string {
  const fromBody = body?.correlationId;
  if (typeof fromBody === 'string' && fromBody.trim() !== '') {
    return fromBody.trim();
  }
  const rawHeader = headers?.get('X-Correlation-Id');
  if (rawHeader !== null && rawHeader !== undefined) {
    const first = rawHeader.split(',')[0]?.trim();
    if (first !== undefined && first !== '') {
      return first;
    }
  }
  return sentCorrelationId;
}

/**
 * Turn a non-OK `Response` into an `ApiProblem`. Never throws, never rejects.
 *
 * Reads the body exactly once with `res.text()` and parses it here, because `res.json()` on a
 * body that is not JSON rejects with a SyntaxError -- and a throw inside the error path erases
 * the failure it was trying to describe. Two of this platform's real responses are not
 * problem+json (see DETAIL 4) and both are tested.
 */
export async function parseProblem(
  res: Response,
  sentCorrelationId: string,
  context: RequestContext,
): Promise<ApiProblem> {
  const rawText = await readBodyText(res);
  const parsedJson = tryParseJson(rawText);

  /**
   * DETAIL 4 OF 4: a JSON body is not a problem body.
   *
   * The media type decides, and only the media type. The gateway's own 404 for an unrouted
   * path is `Content-Type: application/json` carrying
   * `{"timestamp":...,"path":"/nope","status":404,"error":"Not Found","requestId":"..."}`
   * -- verified live at `GET http://localhost:8080/nope`. That body has an `error` FIELD whose
   * value is the human string `"Not Found"`, so a parser that read `body.error` from any JSON
   * response would produce `code: "Not Found"` and hand the UI a stable code that is neither
   * stable nor a code. It also carries no `X-Correlation-Id` header at all, which is why the
   * sent id is the final fallback.
   */
  if (!isProblemMediaType(res.headers.get('content-type'))) {
    return new ApiProblem({
      status: res.status,
      code: 'GATEWAY_ERROR',
      detail: gatewayDetail(res.status),
      correlationId: readCorrelationId(null, res.headers, sentCorrelationId),
      raw: parsedJson ?? rawText,
      ...context,
    });
  }

  // `safeParse` and a lenient schema: every member is optional, so a truncated or partial
  // problem body still yields something a user can quote a support code from. Code that throws
  // while building an error is the worst kind to debug -- the original failure disappears.
  const parsed = problemDetailSchema.safeParse(parsedJson);
  const body: ProblemDetailBody = parsed.success ? parsed.data : {};

  return new ApiProblem({
    status: res.status,
    /**
     * DETAIL 3 OF 4: where `code` comes from.
     *
     * `error`, then `title`, then the status map. NEVER `message`, and never `detail`.
     * `ApiErrors.of` sets `title` and `error` to the same value on every response this
     * platform produces, so the fallback chain is belt-and-braces for a body from something
     * else in the path -- but the ORDER matters: `error` is the field docs/security.md
     * commits to as stable.
     */
    code: firstNonBlank(body.error, body.title) ?? statusToCode(res.status),
    /**
     * `detail` first, `message` second -- they are the same string on this platform, and RFC
     * 7807's own member is the one to prefer. The final fallback is a generic sentence rather
     * than an empty string, because this text is rendered.
     */
    detail: firstNonBlank(body.detail, body.message) ?? gatewayDetail(res.status),
    instance: body.instance,
    path: body.path,
    correlationId: readCorrelationId(body, res.headers, sentCorrelationId),
    raw: parsedJson ?? rawText,
    ...context,
  });
}

/**
 * A rejected `fetch`: offline, DNS failure, connection reset, or our own 15-second abort.
 *
 * There is no status, so `status` is 0. The abort is separated from the rest only in the
 * DISPLAY TEXT, not in the code -- the brief fixes three synthesised codes and adding a fourth
 * would mean every feature stage's per-code table had a hole in it. A user who waited fifteen
 * seconds and a user with no connection need different words, and words are what `detail` is.
 */
export function networkProblem(
  cause: unknown,
  sentCorrelationId: string,
  context: RequestContext,
): ApiProblem {
  const aborted = cause instanceof DOMException && cause.name === 'AbortError';
  return new ApiProblem({
    status: 0,
    code: 'NETWORK_ERROR',
    detail: aborted
      ? `The request took longer than ${String(Math.round(REQUEST_TIMEOUT_MS / 1000))} seconds and was stopped. It may still have been processed.`
      : 'We could not reach the server. Check your connection and try again.',
    correlationId: sentCorrelationId,
    raw: cause,
    ...context,
  });
}

/**
 * A 2xx whose body no longer matches its schema.
 *
 * IDENTICAL IN DEVELOPMENT AND IN PRODUCTION. A parser that throws in dev and degrades in prod
 * is how a real break stays hidden until a customer finds it; the point of validating at the
 * boundary is that the failure has a field path and a place, and that property is worth
 * nothing if it is switched off exactly where it matters.
 *
 * `issuePaths` is the zod path list, joined -- `content.0.createdAt: expected string, received
 * null`. That string goes to `console.error` and into `detail` for the support surface; the
 * user sees generic copy chosen by `code`.
 */
export function contractViolation(
  issueSummary: string,
  status: number,
  sentCorrelationId: string,
  context: RequestContext,
  raw: unknown,
): ApiProblem {
  return new ApiProblem({
    status,
    code: 'CONTRACT_VIOLATION',
    detail: `The server's response did not match what this version of the app expects (${issueSummary}).`,
    correlationId: sentCorrelationId,
    raw,
    ...context,
  });
}

/* -------------------------------------------------------------------------------------- */
/* Internals                                                                               */
/* -------------------------------------------------------------------------------------- */

function gatewayDetail(status: number): string {
  if (status === 0) {
    return 'We could not reach the server.';
  }
  if (status >= 500) {
    return 'Something between your browser and the service returned an error. Your data is safe.';
  }
  return `The server answered ${String(status)} without an explanation we could read.`;
}

async function readBodyText(res: Response): Promise<string> {
  try {
    return await res.text();
  } catch {
    // A body that fails mid-stream. Not a reason to lose the status and the correlation id.
    return '';
  }
}

function tryParseJson(text: string): unknown {
  if (text.trim() === '') {
    return null;
  }
  try {
    return JSON.parse(text) as unknown;
  } catch {
    // An HTML error page from nginx, or a truncated body. `raw` keeps the text.
    return null;
  }
}

function firstNonBlank(...candidates: readonly (string | undefined)[]): string | undefined {
  for (const candidate of candidates) {
    if (typeof candidate === 'string' && candidate.trim() !== '') {
      return candidate;
    }
  }
  return undefined;
}
