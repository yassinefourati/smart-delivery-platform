import { describe, expect, it } from 'vitest';

import { orderStatusValues, type OrderStatusView } from '../../lib/api/schemas/order';
import { POLL_CEILING_MS, nextPollDelay, pollingGaveUp } from '../polling';

/**
 * A table over every status and the elapsed-time tiers. No fake timers and no rendered tree:
 * the function is pure, so the test is a list of inputs and answers.
 */
describe('nextPollDelay', () => {
  const S = 1_000;
  const M = 60 * S;

  const cases: [OrderStatusView | undefined, number, number | false][] = [
    // The saga in flight backs off: fast at first, gentler as it drags on.
    ['CREATED', 0, 1 * S],
    ['INVENTORY_RESERVATION_PENDING', 9 * S, 1 * S],
    ['INVENTORY_RESERVED', 10 * S, 2 * S],
    ['PAYMENT_PENDING', 29 * S, 2 * S],
    ['CREATED', 30 * S, 5 * S],
    ['PAYMENT_PENDING', 119 * S, 5 * S],
    ['CREATED', 2 * M, 15 * S],
    ['INVENTORY_RESERVED', 15 * M, 15 * S],
    // ...and gives up at the platform's real worst case, not before.
    ['CREATED', 16 * M, false],
    ['PAYMENT_PENDING', 30 * M, false],
    // PAID is followed within seconds by SHIPMENT_CREATED.
    ['PAID', 0, 3 * S],
    ['PAID', 16 * M, false],
    // Human workflow from here: hours or days. Interval polling stops entirely.
    ['SHIPMENT_CREATED', 0, false],
    ['OUT_FOR_DELIVERY', 0, false],
    // Terminal. Nothing will ever change.
    ['DELIVERED', 0, false],
    ['CANCELLED', 0, false],
    ['FAILED', 0, false],
    // A status this build does not know: poll gently, then stop.
    ['UNKNOWN', 0, 15 * S],
    ['UNKNOWN', 16 * M, false],
    // No data yet.
    [undefined, 0, 1 * S],
  ];

  it.each(cases)('%s after %ims -> %s', (status, elapsed, expected) => {
    expect(nextPollDelay(status, elapsed)).toBe(expected);
  });

  it('never polls faster than once a second, for any status at any time', () => {
    for (const status of [...orderStatusValues, 'UNKNOWN' as const]) {
      for (let elapsed = 0; elapsed < 20 * M; elapsed += 7 * S) {
        const delay = nextPollDelay(status, elapsed);
        if (delay !== false) expect(delay).toBeGreaterThanOrEqual(1 * S);
      }
    }
  });

  it('stops for every status by the ceiling, so no tab polls forever', () => {
    for (const status of [...orderStatusValues, 'UNKNOWN' as const]) {
      expect(nextPollDelay(status, POLL_CEILING_MS)).toBe(false);
    }
  });
});

describe('pollingGaveUp', () => {
  it('distinguishes "took too long" from "waiting for a human"', () => {
    expect(pollingGaveUp('CREATED', POLL_CEILING_MS)).toBe(true);
    expect(pollingGaveUp('CREATED', POLL_CEILING_MS - 1)).toBe(false);
    // Stopped polling, but that is correct, not a failure to report.
    expect(pollingGaveUp('SHIPMENT_CREATED', POLL_CEILING_MS)).toBe(false);
    expect(pollingGaveUp('DELIVERED', POLL_CEILING_MS)).toBe(false);
  });
});
