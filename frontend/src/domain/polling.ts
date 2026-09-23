import type { OrderStatusView } from '../lib/api/schemas/order';
import { isSagaInFlight, isTerminal } from './orderStatus';

/**
 * How long to wait before polling an order's status again -- or `false` to stop.
 *
 * A PURE function on purpose: no React, no timers, no clock. It takes how long we have been
 * watching and how many polls we have made, and returns a number. That is what lets the test
 * be a table over every status with no fake timers and no rendered tree, which is the test in
 * this app least likely to be skipped or deleted.
 *
 * The tiers come from what drives each state, not from taste:
 *
 *   - SAGA IN FLIGHT (CREATED .. PAYMENT_PENDING) moves on Kafka in seconds, so poll fast
 *     first and back off: 1s for 10s, 2s to 30s, 5s to 2 minutes, 15s until the ceiling.
 *   - PAID is followed within seconds by SHIPMENT_CREATED (delivery-service reacts to
 *     payment.completed), so 3s.
 *   - SHIPMENT_CREATED and OUT_FOR_DELIVERY advance on a HUMAN -- an admin assigning an agent,
 *     the agent completing the delivery. Hours to days. Interval polling STOPS; the page
 *     refetches on window focus and offers a Check now button. Polling those every second
 *     would be a load generator, not a feature.
 *   - DELIVERED, CANCELLED, FAILED never change again. Stop.
 *   - UNKNOWN (a status this build does not recognise) polls gently at 15s to the ceiling.
 *
 * THE CEILING IS 16 MINUTES, and it is the platform's real worst case rather than a guess:
 * order-service's stuck-saga reaper resumes a saga after `saga.stuck-threshold` (5m) and gives
 * up after `saga.max-attempts` (3), checking every `saga.reaper-interval-ms` (60s) -- see
 * order-service/src/main/resources/application.yml and ADR 008. A UI that gave up at two
 * minutes and said "failed" would be lying about an order that might still complete. If a
 * deployment raises SAGA_STUCK_THRESHOLD or SAGA_MAX_ATTEMPTS, this number is premature and
 * nothing will catch it -- that is documentation, not enforcement, and it is said here so the
 * next person to change those properties finds it.
 */
export const POLL_CEILING_MS = 16 * 60_000;

export function nextPollDelay(
  status: OrderStatusView | undefined,
  elapsedMs: number,
): number | false {
  // No data yet: the first fetch is already in flight, so this only matters on an error retry.
  if (status === undefined) {
    return 1_000;
  }
  if (isTerminal(status)) {
    return false;
  }
  if (status === 'SHIPMENT_CREATED' || status === 'OUT_FOR_DELIVERY') {
    return false;
  }
  if (elapsedMs >= POLL_CEILING_MS) {
    return false;
  }
  if (status === 'PAID') {
    return 3_000;
  }
  if (status === 'UNKNOWN') {
    return 15_000;
  }
  if (isSagaInFlight(status)) {
    if (elapsedMs < 10_000) return 1_000;
    if (elapsedMs < 30_000) return 2_000;
    if (elapsedMs < 120_000) return 5_000;
    return 15_000;
  }
  // Every wire status is handled above; this is reachable only if one is added without
  // updating this function, and the safe answer to "I don't know" is to stop hammering.
  return false;
}

/**
 * True when polling stopped because the saga took longer than the platform's own worst case,
 * as opposed to reaching a state where waiting is simply the right thing. The two need
 * different copy: one is "this is taking longer than it should, here is a support code", the
 * other is "your order is with the delivery team".
 */
export function pollingGaveUp(status: OrderStatusView | undefined, elapsedMs: number): boolean {
  return (
    status !== undefined &&
    (isSagaInFlight(status) || status === 'UNKNOWN' || status === 'PAID') &&
    elapsedMs >= POLL_CEILING_MS
  );
}
