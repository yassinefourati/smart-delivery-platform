import { useMutation, useQueryClient } from '@tanstack/react-query';

import { createOrder } from '../../lib/api/endpoints';
import { isApiProblem } from '../../lib/api/problem';
import { mutationKeys, queryKeys } from '../../lib/api/queryKeys';
import type { CreateOrderRequest, OrderResponse } from '../../lib/api/schemas/order';

/**
 * Is this failure one where the order MAY exist? Then the only safe retry carries the same key.
 * 401 belongs here too: a token that expired in flight says nothing about whether the POST landed.
 */
export function isAmbiguousFailure(error: unknown): boolean {
  if (!isApiProblem(error)) return false;
  return (
    error.code === 'NETWORK_ERROR' ||
    error.code === 'GATEWAY_ERROR' ||
    error.status === 401 ||
    error.status >= 500
  );
}

/**
 * POST /api/v1/orders with `Idempotency-Key`.
 *
 * THE AUTOMATIC RETRY IS SAFE ONLY BECAUSE OF THE KEY. It retries a network failure or a
 * 502/503/504 at most twice, with the SAME key: order-service answers a replay with 201 and the
 * ORIGINAL order id, so a retry of a POST that actually landed cannot create a second order.
 * Without the header this retry would be the duplicate-order bug. Nothing else is retried.
 *
 * `mutationKey` includes the checkout key, so a second mount of /checkout with the same
 * persisted key shares the in-flight mutation. That and the disabled button are the belt; the
 * header is the braces.
 *
 * A 201 is not "success" in the business sense, only "the saga has started". The caller seeds
 * the status cache from the response so the tracking page opens at step one with no spinner.
 */
export function usePlaceOrder(checkoutKey: string | null) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationKey: mutationKeys.placeOrder(checkoutKey ?? 'none'),
    mutationFn: (body: CreateOrderRequest) => {
      if (checkoutKey === null)
        throw new Error('Place order was reachable without an idempotency key.');
      return createOrder(body, checkoutKey);
    },
    retry: (failureCount, error) =>
      failureCount < 2 &&
      isApiProblem(error) &&
      (error.code === 'NETWORK_ERROR' || [502, 503, 504].includes(error.status)),
    onSuccess: (order: OrderResponse) => {
      queryClient.setQueryData(queryKeys.orders.detail(order.id), order);
      queryClient.setQueryData(queryKeys.orders.status(order.id), {
        orderId: order.id,
        status: order.status,
      });
      void queryClient.invalidateQueries({ queryKey: queryKeys.orders.all() });
    },
  });
}
