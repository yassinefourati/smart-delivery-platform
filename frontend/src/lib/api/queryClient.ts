/**
 * The single `QueryClient`, and the retry policy that is the reason it lives in one file.
 *
 * CONSTRUCTED AT MODULE SCOPE, NOT IN A RENDER BODY. `new QueryClient()` inside a component is a
 * NEW CACHE ON EVERY RENDER: every query refetches, `setQueryData` from a mutation lands in a
 * cache the next render throws away, and the app looks like it has a caching bug when it has no
 * cache at all. `src/App.tsx` imports this instance.
 *
 * WHY THE RETRY POLICY IS HERE AND NOT SPREAD ACROSS CALL SITES. The interaction between "retry
 * a failed request" and "POST /api/v1/orders is only idempotent if the SAME key is reused" is
 * exactly where the duplicate-order bug lives. That decision deserves to be readable in one
 * place, next to the reason, rather than inferred from six `useQuery` options objects.
 */

import { QueryClient } from '@tanstack/react-query';
import { isApiProblem } from './problem';

/**
 * How long a response stays fresh, per kind of data. Feature stages import these rather than
 * writing numbers, so "how stale may the catalog be" is answered once.
 *
 * Each number is a judgement about what a user loses by seeing an old value:
 *  - CATALOG (30s): a price or a name changing under a browsing session is harmless, because the
 *    server snapshots the authoritative price at order creation anyway.
 *  - CATEGORIES (10min): an admin adds one a few times a year.
 *  - INVENTORY (15s): the shortest of the reads, because this is the number that decides whether
 *    Place Order is blocked. Still ADVISORY -- the server is the only arbiter of a reservation.
 *  - ORDER_STATUS (0): the polled query. Anything above zero fights `refetchInterval`.
 *  - ORDER_DETAIL (5s): refetched once per observed transition, so items and totals follow the
 *    status without being dragged across the wire on every poll.
 *  - ACCOUNT (5min): a profile and an address list the user changes deliberately, in this tab,
 *    and whose mutations invalidate their own keys.
 */
export const STALE_TIME = {
  CATALOG: 30_000,
  CATEGORIES: 600_000,
  INVENTORY: 15_000,
  ORDER_STATUS: 0,
  ORDER_DETAIL: 5_000,
  ACCOUNT: 300_000,
} as const;

/** Beyond this many attempts a transient failure is not transient. */
const MAX_RETRIES = 3;

/**
 * The one 4xx that IS retried, once. docs/security.md says of CONCURRENT_MODIFICATION, in as
 * many words, that "the caller should retry" -- it is an optimistic-lock loss, so the second
 * attempt reads the winner's row and usually succeeds. ~300ms is long enough for the winning
 * transaction to have committed and short enough that the user does not see a second spinner.
 */
const CONCURRENT_MODIFICATION_RETRY_MS = 300;

/** Statuses that mean "the request did not reach a healthy service", so retrying is meaningful. */
const RETRYABLE_STATUSES = new Set([502, 503, 504]);

/**
 * Should a failed READ be retried?
 *
 * NEVER A 4xx, with exactly one exception. A VALIDATION_ERROR will not succeed on attempt two, a
 * 404 will not become a 200, and retrying a 403 is three times the log noise with zero chance of
 * success -- while a 401 retried is a token that is still expired, and the correct response to
 * that is a single-flight redirect to `/login?next=`, not a second request.
 *
 * ALSO NEVER A 500. A 500 is `INTERNAL_ERROR` -- a bug, not congestion; retrying it turns one
 * stack trace in the server log into three.
 *
 * WHAT IS RETRIED: a rejected fetch (`NETWORK_ERROR`, including our own 15s abort), a
 * `GATEWAY_ERROR`, and a 502/503/504. `SERVICE_UNAVAILABLE` matters most of the three:
 * docs/resilience.md is clear that an open Resilience4j circuit closes again, so this really is
 * transient, and the UI says so in amber rather than red -- because the difference between a user
 * waiting and a user placing a SECOND order is the wording on that screen.
 *
 * A non-`ApiProblem` error is a programming error thrown inside a query function. It is not
 * retried: repeating it three times only makes the stack trace harder to find.
 */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (!isApiProblem(error)) {
    return false;
  }
  if (error.code === 'CONCURRENT_MODIFICATION') {
    return failureCount < 1;
  }
  if (error.status >= 400 && error.status < 500) {
    return false;
  }
  if (
    error.code === 'NETWORK_ERROR' ||
    error.code === 'GATEWAY_ERROR' ||
    RETRYABLE_STATUSES.has(error.status)
  ) {
    return failureCount < MAX_RETRIES;
  }
  return false;
}

/**
 * How long to wait before the next attempt.
 *
 * Exponential with JITTER, and the jitter is not decoration: without it, every query that failed
 * during a brief outage retries at the same millisecond, so the moment the service recovers it
 * takes a synchronised burst from every open tab. Capped at 8s so a user who is watching does not
 * sit through a 16-second gap.
 */
export function retryDelay(failureCount: number, error: unknown): number {
  if (isApiProblem(error) && error.code === 'CONCURRENT_MODIFICATION') {
    return CONCURRENT_MODIFICATION_RETRY_MS;
  }
  const exponential = Math.min(1000 * 2 ** failureCount, 8000);
  return exponential + Math.random() * 250;
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: shouldRetryQuery,
      retryDelay,

      /**
       * `refetchOnWindowFocus: true` and `refetchIntervalInBackground: false` are a PAIR, and
       * together they give the polling design its behaviour for free: while the tab is hidden the
       * saga poll stops entirely, and the moment the user comes back every stale query refetches
       * once. A hidden tab polling a Kafka saga every second for sixteen minutes is a load
       * generator; a tab that catches up instantly on return is the feature.
       */
      refetchOnWindowFocus: true,
      refetchIntervalInBackground: false,

      /**
       * DELIBERATELY NOT SET GLOBALLY: `throwOnError`.
       *
       * It is per-query, because the choice is per-screen and there is a written rule for it:
       * throw to an error boundary when the screen is MEANINGLESS without the data, render inline
       * when the screen is still useful. An order detail page without the order is meaningless --
       * throw. Without its stock figure it is fine -- render inline. A global `throwOnError: true`
       * would blank a whole product page because one advisory inventory read failed.
       */

      // 0 by default and overridden per query from STALE_TIME above. Naming it here rather than
      // relying on the library default makes the override sites obviously deliberate.
      staleTime: 0,
    },

    mutations: {
      /**
       * FALSE, AND THIS IS THE MOST IMPORTANT LINE IN THE FILE.
       *
       * An automatically retried write is a second attempt at something that may already have
       * landed. For `POST /api/v1/orders` that is only safe because the SAME `Idempotency-Key` is
       * reused -- `UNIQUE (user_id, idempotency_key)` means a concurrent loser re-reads the
       * winner's row -- and for `POST /api/v1/orders/{id}/cancel` and
       * `POST /api/v1/deliveries/{id}/complete` there is NO key on the endpoint at all, so there
       * is nothing making a retry safe.
       *
       * So retries are opted into per mutation by the one that can prove it is safe, rather than
       * switched on for all of them here. Checkout's `placeOrder` sets its own `retry` for network
       * failures and 502/503/504 WHILE REUSING the persisted key; nothing else retries.
       */
      retry: false,
    },
  },
});

/**
 * Drop every cached response. Called by `logout`.
 *
 * `clear()` and not `invalidateQueries()`: invalidating marks entries stale but LEAVES THE DATA,
 * so the next render of a screen the previous user had open paints their orders from cache before
 * the refetch replaces them. On a shared machine that is a visible leak of one account's data to
 * the next person, and no server-side check can catch it because no request is made. The keys
 * include `userId` precisely so this cannot happen by accident, and this makes it impossible on
 * purpose too.
 */
export function clearAllCachedData(): void {
  queryClient.clear();
}
