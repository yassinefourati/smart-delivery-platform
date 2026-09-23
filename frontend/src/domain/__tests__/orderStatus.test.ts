import { describe, expect, it } from 'vitest';

import { orderStatusValues, type OrderStatusView } from '../../lib/api/schemas/order';
import { canCancel, cancelInvolvesRefund, isSagaInFlight, isTerminal } from '../orderStatus';

/**
 * The cancellation table, transcribed from OrderStatus.java's transition table. If this test
 * and that file ever disagree, the Java file is right and this table is the bug.
 */
describe('canCancel', () => {
  const expected: Record<OrderStatusView, boolean> = {
    CREATED: true,
    INVENTORY_RESERVATION_PENDING: true,
    INVENTORY_RESERVED: true,
    PAYMENT_PENDING: true,
    PAID: true,
    SHIPMENT_CREATED: false,
    OUT_FOR_DELIVERY: false,
    DELIVERED: false,
    CANCELLED: false,
    FAILED: false,
    UNKNOWN: false,
  };

  it.each(Object.entries(expected))('%s -> %s', (status, cancellable) => {
    expect(canCancel(status as OrderStatusView)).toBe(cancellable);
  });

  it('covers every status the server can send, so a new one cannot slip through', () => {
    for (const status of orderStatusValues) {
      expect(expected).toHaveProperty(status);
    }
  });
});

describe('the other predicates', () => {
  it('only PAID involves a refund, because only then has money moved', () => {
    const refunding = [...orderStatusValues, 'UNKNOWN' as const].filter(cancelInvolvesRefund);
    expect(refunding).toEqual(['PAID']);
  });

  it('terminal states are exactly the three that never change again', () => {
    expect([...orderStatusValues].filter(isTerminal).sort()).toEqual([
      'CANCELLED',
      'DELIVERED',
      'FAILED',
    ]);
  });

  it('a terminal state is never cancellable', () => {
    for (const status of orderStatusValues) {
      if (isTerminal(status)) expect(canCancel(status)).toBe(false);
    }
  });

  it('the saga is in flight only before payment completes', () => {
    expect([...orderStatusValues].filter(isSagaInFlight)).toEqual([
      'CREATED',
      'INVENTORY_RESERVATION_PENDING',
      'INVENTORY_RESERVED',
      'PAYMENT_PENDING',
    ]);
  });
});
