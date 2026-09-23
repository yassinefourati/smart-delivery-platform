import { describe, expect, it } from 'vitest';

import { type CheckoutKeyEvent, nextCheckoutKey } from '../idempotency';

const mint = () => 'fresh';

/**
 * The rotate/keep matrix. Each row is a real situation, and the "keep" rows are the ones that
 * matter most: rotating on any of them turns a retry into a second order.
 */
describe('nextCheckoutKey', () => {
  const rows: [string, string | null, CheckoutKeyEvent, boolean, string | null][] = [
    ['entering checkout with items and no key mints one', null, 'checkoutEntered', true, 'fresh'],
    [
      'entering checkout again KEEPS the key (remount, refresh, back button)',
      'k1',
      'checkoutEntered',
      true,
      'k1',
    ],
    ['entering checkout with an empty cart mints nothing', null, 'checkoutEntered', false, null],
    ['changing the order rotates an existing key', 'k1', 'orderChanged', true, 'fresh'],
    ['editing the cart before checkout mints nothing early', null, 'orderChanged', true, null],
    ['emptying the cart drops the key', 'k1', 'orderChanged', false, null],
    ['a 201 spends the key', 'k1', 'orderCreated', false, null],
    ['start over mints a new key', 'k1', 'startOver', true, 'fresh'],
    ['a 5xx, 401, timeout or network failure KEEPS the key', 'k1', 'ambiguousFailure', true, 'k1'],
    ['a 409 KEEPS the key rather than silently re-POSTing', 'k1', 'conflict', true, 'k1'],
  ];

  it.each(rows)('%s', (_label, current, event, hasLines, expected) => {
    expect(nextCheckoutKey(current, event, hasLines, mint)).toBe(expected);
  });

  it('two checkout entries in a row -- the double-mount case -- produce one key', () => {
    let calls = 0;
    const counting = () => `key-${++calls}`;
    const first = nextCheckoutKey(null, 'checkoutEntered', true, counting);
    const second = nextCheckoutKey(first, 'checkoutEntered', true, counting);
    expect(second).toBe(first);
    expect(calls).toBe(1);
  });
});
