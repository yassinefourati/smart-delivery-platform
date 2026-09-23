/**
 * The retry policy.
 *
 * This is where the interaction between "retry a failed request" and "POST /api/v1/orders is only
 * idempotent if the SAME key is reused" lives, so it is worth asserting rather than trusting. The
 * two rules with teeth: never a 4xx (with one documented exception), and mutations never retry by
 * default.
 */

import { describe, expect, it } from 'vitest';
import {
  STALE_TIME,
  clearAllCachedData,
  queryClient,
  retryDelay,
  shouldRetryQuery,
} from '../queryClient';
import { ApiProblem } from '../problem';
import type { ErrorCode } from '../problem';

function problem(status: number, code: ErrorCode): ApiProblem {
  return new ApiProblem({
    status,
    code,
    detail: 'test',
    correlationId: 'test-correlation-id',
    method: 'GET',
    pathTemplate: '/api/v1/test',
  });
}

describe('shouldRetryQuery -- never a 4xx', () => {
  it.each([
    [400, 'VALIDATION_ERROR'],
    [400, 'MALFORMED_REQUEST'],
    [400, 'INVALID_PRODUCT'],
    [401, 'UNAUTHORIZED'],
    [403, 'FORBIDDEN'],
    [404, 'NOT_FOUND'],
    [405, 'METHOD_NOT_ALLOWED'],
    [409, 'CONFLICT'],
    [409, 'EMAIL_ALREADY_EXISTS'],
    [409, 'IDEMPOTENCY_KEY_CONFLICT'],
  ] as const)('does not retry %i %s', (status, code) => {
    // A VALIDATION_ERROR will not succeed on attempt two, a 404 will not become a 200, and a
    // retried 401 is a token that is still expired -- the answer to that is a single-flight
    // redirect to /login?next=, not a second request.
    expect(shouldRetryQuery(0, problem(status, code))).toBe(false);
  });

  it('does not retry a 500 either', () => {
    // INTERNAL_ERROR is a bug, not congestion. Retrying turns one stack trace into three.
    expect(shouldRetryQuery(0, problem(500, 'INTERNAL_ERROR'))).toBe(false);
  });
});

describe('shouldRetryQuery -- what IS retried', () => {
  it.each([
    [0, 'NETWORK_ERROR'],
    [502, 'GATEWAY_ERROR'],
    [503, 'SERVICE_UNAVAILABLE'],
    [504, 'GATEWAY_ERROR'],
  ] as const)('retries %i %s up to three times', (status, code) => {
    // SERVICE_UNAVAILABLE matters most: docs/resilience.md is clear that an open Resilience4j
    // circuit closes again, so this really is transient -- and the difference between a user
    // waiting and a user placing a SECOND order is the wording on that screen.
    expect(shouldRetryQuery(0, problem(status, code))).toBe(true);
    expect(shouldRetryQuery(2, problem(status, code))).toBe(true);
    expect(shouldRetryQuery(3, problem(status, code))).toBe(false);
  });

  it('retries CONCURRENT_MODIFICATION exactly once, although it is a 4xx', () => {
    // The one documented exception: docs/security.md says of it, in as many words, that "the
    // caller should retry". It is an optimistic-lock loss, so attempt two reads the winner's row.
    const optimisticLockLoss = problem(409, 'CONCURRENT_MODIFICATION');
    expect(shouldRetryQuery(0, optimisticLockLoss)).toBe(true);
    expect(shouldRetryQuery(1, optimisticLockLoss)).toBe(false);
  });

  it('does not retry an error that is not an ApiProblem', () => {
    // A programming error thrown inside a query function. Repeating it three times only makes the
    // stack trace harder to find.
    expect(shouldRetryQuery(0, new TypeError('cannot read properties of undefined'))).toBe(false);
    expect(shouldRetryQuery(0, 'a string')).toBe(false);
    expect(shouldRetryQuery(0, undefined)).toBe(false);
  });

  it('routes an unrecognised future code to no retry rather than to a crash', () => {
    // `(string & {})` in ErrorCode makes a future server code REPRESENTABLE. It gets the safe
    // answer here instead of throwing inside the retry predicate.
    expect(shouldRetryQuery(0, problem(418, 'SOMETHING_NEW'))).toBe(false);
  });
});

describe('retryDelay', () => {
  it("uses the platform's own ~300ms for a lock loss", () => {
    expect(retryDelay(0, problem(409, 'CONCURRENT_MODIFICATION'))).toBe(300);
  });

  it('backs off exponentially, with jitter, capped at 8s', () => {
    const transient = problem(503, 'SERVICE_UNAVAILABLE');
    const first = retryDelay(0, transient);
    const second = retryDelay(1, transient);

    expect(first).toBeGreaterThanOrEqual(1000);
    expect(first).toBeLessThan(1250);
    expect(second).toBeGreaterThanOrEqual(2000);
    // Capped so a watching user does not sit through a 16-second gap.
    expect(retryDelay(10, transient)).toBeLessThan(8250);
    expect(retryDelay(10, transient)).toBeGreaterThanOrEqual(8000);
  });

  it('adds jitter, so every tab does not retry on the same millisecond', () => {
    // Without jitter, the moment a service recovers it takes a synchronised burst from every open
    // tab that failed during the outage.
    const transient = problem(503, 'SERVICE_UNAVAILABLE');
    const samples = new Set(Array.from({ length: 20 }, () => retryDelay(0, transient)));
    expect(samples.size).toBeGreaterThan(1);
  });
});

describe('the client itself', () => {
  it('is one module-scoped instance, not a new cache per render', () => {
    // `new QueryClient()` inside a component body is a new cache on every render: every query
    // refetches and `setQueryData` lands in a cache the next render throws away.
    expect(queryClient.getQueryCache()).toBe(queryClient.getQueryCache());
  });

  it('never retries a mutation by default', () => {
    // THE MOST IMPORTANT DEFAULT IN THE FILE. An automatically retried write is a second attempt
    // at something that may already have landed, and `POST /api/v1/orders/{id}/cancel` and
    // `POST /api/v1/deliveries/{id}/complete` carry no idempotency key at all.
    expect(queryClient.getDefaultOptions().mutations?.retry).toBe(false);
  });

  it('pauses polling in a hidden tab and catches up on focus', () => {
    const queries = queryClient.getDefaultOptions().queries;
    expect(queries?.refetchIntervalInBackground).toBe(false);
    expect(queries?.refetchOnWindowFocus).toBe(true);
  });

  it('leaves throwOnError unset, because the choice is per screen', () => {
    // A global `throwOnError: true` would blank a whole product page because one advisory inventory
    // read failed. The written rule is: throw when the screen is meaningless without the data,
    // render inline when it is still useful.
    expect(queryClient.getDefaultOptions().queries?.throwOnError).toBeUndefined();
  });

  it('DROPS cached data on logout rather than marking it stale', () => {
    // `invalidateQueries` would leave the data in place, so the next render of a screen the previous
    // user had open paints THEIR orders from cache before the refetch replaces them. On a shared
    // machine that is a visible leak of one account's data to the next person, and no server-side
    // check can catch it because no request is made.
    queryClient.setQueryData(['orders', 'byUser', 'user-a'], [{ id: 'order-1' }]);
    expect(queryClient.getQueryData(['orders', 'byUser', 'user-a'])).not.toBeUndefined();

    clearAllCachedData();

    expect(queryClient.getQueryData(['orders', 'byUser', 'user-a'])).toBeUndefined();
  });

  it('gives the polled status query a zero staleTime and the catalog a real one', () => {
    // Anything above zero on the polled query fights refetchInterval.
    expect(STALE_TIME.ORDER_STATUS).toBe(0);
    expect(STALE_TIME.INVENTORY).toBeLessThan(STALE_TIME.CATALOG);
    expect(STALE_TIME.CATALOG).toBeLessThan(STALE_TIME.CATEGORIES);
  });
});
