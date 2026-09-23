import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';

import { nextPollDelay, pollingGaveUp } from '../../domain/polling';
import { getOrderStatus } from '../../lib/api/endpoints';
import { isApiProblem } from '../../lib/api/problem';
import { STALE_TIME } from '../../lib/api/queryClient';
import { queryKeys } from '../../lib/api/queryKeys';
import { useNow } from '../../lib/time/useNow';

/** 401 ends the session, 403 and 404 will not change on a retry: polling any of them is noise. */
const STOP_ON = new Set([401, 403, 404]);

/**
 * Polls `GET /api/v1/orders/:id/status` -- the cheap endpoint -- on the schedule in
 * domain/polling.ts. No scheduling logic lives here; that file owns the numbers and the reasons.
 *
 * `sinceMs` is when the clock started: the order's createdAt, or the moment the person pressed
 * "Check again" after polling gave up. Polling pauses in a hidden tab (the client default) and
 * catches up on focus.
 *
 * When the status changes, the full order is invalidated ONCE -- not refetched on every tick.
 */
export function useOrderStatus(orderId: string, sinceMs: number, enabled = true) {
  const queryClient = useQueryClient();
  const query = useQuery({
    queryKey: queryKeys.orders.status(orderId),
    queryFn: ({ signal }) => getOrderStatus(orderId, { signal }),
    staleTime: STALE_TIME.ORDER_STATUS,
    enabled,
    refetchInterval: (q) => {
      const error = q.state.error;
      if (isApiProblem(error) && STOP_ON.has(error.status)) return false;
      return nextPollDelay(q.state.data?.status, Math.max(0, Date.now() - sinceMs));
    },
  });

  const status = query.data?.status;
  const previous = useRef(status);
  useEffect(() => {
    if (previous.current !== undefined && status !== undefined && previous.current !== status) {
      void queryClient.invalidateQueries({ queryKey: queryKeys.orders.detail(orderId) });
    }
    previous.current = status;
  }, [status, orderId, queryClient]);

  const now = useNow(5_000);
  return { ...query, gaveUp: pollingGaveUp(status, now - sinceMs) };
}
