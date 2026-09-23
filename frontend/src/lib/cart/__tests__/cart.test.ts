import { describe, expect, it } from 'vitest';

import {
  CART_STORAGE_KEY,
  EMPTY_CART,
  type CartAction,
  type CartState,
  cartReducer,
  estimatedTotal,
  loadCart,
  saveCart,
} from '../cart';

let n = 0;
const mint = () => `key-${++n}`;
const widget = { productId: 'p1', sku: 'W-1', nameAtAdd: 'Widget', priceAtAdd: 10 };
const gadget = { productId: 'p2', sku: 'G-1', nameAtAdd: 'Gadget', priceAtAdd: 4.5 };

const run = (actions: CartAction[], from: CartState = EMPTY_CART) =>
  actions.reduce(cartReducer, from);

describe('cartReducer', () => {
  it('adding the same product twice merges the lines', () => {
    const cart = run([
      { type: 'add', line: widget, quantity: 1, mint },
      { type: 'add', line: widget, quantity: 2, mint },
    ]);
    expect(cart.lines).toEqual([{ ...widget, quantity: 3 }]);
  });

  it('clamps quantities to the server minimum and a sane maximum', () => {
    const cart = run([
      { type: 'add', line: widget, quantity: 1, mint },
      { type: 'setQuantity', productId: 'p1', quantity: 0, mint },
    ]);
    expect(cart.lines[0]?.quantity).toBe(1);
    expect(run([{ type: 'add', line: widget, quantity: 500, mint }]).lines[0]?.quantity).toBe(99);
  });

  it('does not mint a key before checkout, so a second order never reuses a spent one', () => {
    const cart = run([{ type: 'add', line: widget, quantity: 1, mint }]);
    expect(cart.checkoutKey).toBeNull();
  });

  it('mints a key on entering checkout and keeps it across a second entry', () => {
    const entered = run([
      { type: 'add', line: widget, quantity: 1, mint },
      { type: 'enterCheckout', mint },
    ]);
    expect(entered.checkoutKey).not.toBeNull();
    expect(cartReducer(entered, { type: 'enterCheckout', mint }).checkoutKey).toBe(
      entered.checkoutKey,
    );
  });

  it('rotates the key on any change to what is being ordered', () => {
    const entered = run([
      { type: 'add', line: widget, quantity: 1, mint },
      { type: 'enterCheckout', mint },
    ]);
    const changes: CartAction[] = [
      { type: 'setQuantity', productId: 'p1', quantity: 2, mint },
      { type: 'add', line: gadget, quantity: 1, mint },
      { type: 'setAddress', addressId: 'a1', mint },
      { type: 'acceptPrices', prices: { p1: 12 }, mint },
    ];
    for (const change of changes) {
      const next = cartReducer(entered, change);
      expect(next.checkoutKey, change.type).not.toBeNull();
      expect(next.checkoutKey, change.type).not.toBe(entered.checkoutKey);
    }
  });

  it('choosing the address that is already chosen is not a change', () => {
    const entered = run([
      { type: 'add', line: widget, quantity: 1, mint },
      { type: 'setAddress', addressId: 'a1', mint },
      { type: 'enterCheckout', mint },
    ]);
    expect(cartReducer(entered, { type: 'setAddress', addressId: 'a1', mint }).checkoutKey).toBe(
      entered.checkoutKey,
    );
  });

  it('a 201 empties the cart and spends the key, but keeps the owner', () => {
    const owned = run([
      { type: 'session', userId: 'u1' },
      { type: 'add', line: widget, quantity: 1, mint },
      { type: 'enterCheckout', mint },
      { type: 'orderCreated' },
    ]);
    expect(owned).toEqual({ ...EMPTY_CART, ownerUserId: 'u1' });
  });

  it("adopts an anonymous cart on login, keeps its own, and drops somebody else's", () => {
    const anonymous = run([{ type: 'add', line: widget, quantity: 1, mint }]);
    const adopted = cartReducer(anonymous, { type: 'session', userId: 'u1' });
    expect(adopted.lines).toHaveLength(1);
    expect(cartReducer(adopted, { type: 'session', userId: 'u1' })).toBe(adopted);
    const stranger = cartReducer(adopted, { type: 'session', userId: 'u2' });
    expect(stranger.lines).toHaveLength(0);
    expect(stranger.ownerUserId).toBe('u2');
  });

  it('removing the last line forgets the address and the key', () => {
    const cart = run([
      { type: 'add', line: widget, quantity: 1, mint },
      { type: 'setAddress', addressId: 'a1', mint },
      { type: 'enterCheckout', mint },
      { type: 'remove', productId: 'p1', mint },
    ]);
    expect(cart.shippingAddressId).toBeNull();
    expect(cart.checkoutKey).toBeNull();
  });

  it('estimates the total from the prices captured at add time', () => {
    const cart = run([
      { type: 'add', line: widget, quantity: 2, mint },
      { type: 'add', line: gadget, quantity: 3, mint },
    ]);
    expect(estimatedTotal(cart.lines)).toBeCloseTo(33.5);
  });
});

describe('persistence', () => {
  const memory = () => {
    const store = new Map<string, string>();
    return {
      getItem: (k: string) => store.get(k) ?? null,
      setItem: (k: string, v: string) => void store.set(k, v),
      removeItem: (k: string) => void store.delete(k),
      store,
    };
  };

  it('round-trips a cart, including its idempotency key -- which is why a reload cannot mint a second one', () => {
    const storage = memory();
    const cart = run([
      { type: 'add', line: widget, quantity: 2, mint },
      { type: 'enterCheckout', mint },
    ]);
    saveCart(storage, cart);
    expect(loadCart(storage)).toEqual(cart);
  });

  it('removes the entry rather than storing an empty cart', () => {
    const storage = memory();
    saveCart(storage, run([{ type: 'add', line: widget, quantity: 1, mint }]));
    saveCart(storage, EMPTY_CART);
    expect(storage.store.has(CART_STORAGE_KEY)).toBe(false);
  });

  it.each([
    ['garbage', 'not json'],
    ['another version', JSON.stringify({ ...EMPTY_CART, version: 2 })],
    [
      'a hand-edited quantity',
      JSON.stringify({ ...EMPTY_CART, lines: [{ ...widget, quantity: -4 }] }),
    ],
  ])('treats %s as an empty cart rather than throwing', (_label, raw) => {
    expect(loadCart({ getItem: () => raw })).toEqual(EMPTY_CART);
  });

  it('survives storage that throws, as private browsing modes do', () => {
    const hostile = {
      getItem: () => {
        throw new Error('SecurityError');
      },
      setItem: () => {
        throw new Error('QuotaExceededError');
      },
      removeItem: () => undefined,
    };
    expect(loadCart(hostile)).toEqual(EMPTY_CART);
    expect(() =>
      saveCart(hostile, run([{ type: 'add', line: widget, quantity: 1, mint }])),
    ).not.toThrow();
  });
});
