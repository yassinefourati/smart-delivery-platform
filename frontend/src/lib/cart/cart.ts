import { z } from 'zod';

import { nextCheckoutKey } from '../../domain/idempotency';

/**
 * The cart. Client-side only, because there is no cart endpoint: `POST /api/v1/orders` takes
 * its items directly.
 *
 * Persisted to localStorage under `sdp.cart.v1`, so it SURVIVES A RELOAD. That is deliberate,
 * and it is what makes the memory-only access token affordable (ADR 012): a refresh signs the
 * user out, but it does not throw away what they were about to buy. The cart is the expensive
 * state; the token is the cheap one.
 *
 * The idempotency key lives in here, not beside it, because its lifetime is bound to what is
 * being ordered: an edit to the lines or the address rotates it (see src/domain/idempotency.ts).
 *
 * `priceAtAdd` is an ESTIMATE, and the UI labels it as one. order-service snapshots the
 * server's own price when it creates the order, so the only authoritative total is
 * `OrderResponse.totalAmount` after the 201. Checkout re-prices every line before enabling
 * Place Order, and a difference blocks the order until the user accepts the new prices.
 */
export const CART_STORAGE_KEY = 'sdp.cart.v1';

export interface CartLine {
  readonly productId: string;
  readonly sku: string;
  readonly nameAtAdd: string;
  readonly priceAtAdd: number;
  readonly quantity: number;
}

export interface CartState {
  readonly version: 1;
  /** Whose cart this is. null for an anonymous browser; set on login. */
  readonly ownerUserId: string | null;
  readonly lines: readonly CartLine[];
  readonly shippingAddressId: string | null;
  readonly checkoutKey: string | null;
}

export const EMPTY_CART: CartState = {
  version: 1,
  ownerUserId: null,
  lines: [],
  shippingAddressId: null,
  checkoutKey: null,
};

/** Mirrors OrderItemRequest's @Min(1). The server is still the only authority. */
export const MIN_QUANTITY = 1;
/** A sanity cap for the input, not a business rule the server enforces. */
export const MAX_QUANTITY = 99;

export type CartAction =
  | { type: 'add'; line: Omit<CartLine, 'quantity'>; quantity: number; mint: () => string }
  | { type: 'setQuantity'; productId: string; quantity: number; mint: () => string }
  | { type: 'remove'; productId: string; mint: () => string }
  | { type: 'setAddress'; addressId: string | null; mint: () => string }
  | { type: 'acceptPrices'; prices: Readonly<Record<string, number>>; mint: () => string }
  | { type: 'enterCheckout'; mint: () => string }
  | { type: 'startOver'; mint: () => string }
  | { type: 'orderCreated' }
  /** A login: adopt an anonymous cart, or drop one that belongs to somebody else. */
  | { type: 'session'; userId: string }
  /** Logout, or a mismatched owner. Clears everything including the key. */
  | { type: 'clear' };

const clampQuantity = (q: number) => Math.min(MAX_QUANTITY, Math.max(MIN_QUANTITY, Math.trunc(q)));

/** Anything that changes the body of the order rotates the key -- the server would 409 otherwise. */
function withChange(state: CartState, next: Partial<CartState>, mint: () => string): CartState {
  const merged = { ...state, ...next };
  return {
    ...merged,
    checkoutKey: nextCheckoutKey(state.checkoutKey, 'orderChanged', merged.lines.length > 0, mint),
  };
}

export function cartReducer(state: CartState, action: CartAction): CartState {
  switch (action.type) {
    case 'add': {
      const existing = state.lines.find((l) => l.productId === action.line.productId);
      const lines = existing
        ? state.lines.map((l) =>
            l.productId === action.line.productId
              ? { ...l, quantity: clampQuantity(l.quantity + action.quantity) }
              : l,
          )
        : [...state.lines, { ...action.line, quantity: clampQuantity(action.quantity) }];
      return withChange(state, { lines }, action.mint);
    }
    case 'setQuantity': {
      const lines = state.lines.map((l) =>
        l.productId === action.productId ? { ...l, quantity: clampQuantity(action.quantity) } : l,
      );
      return withChange(state, { lines }, action.mint);
    }
    case 'remove': {
      const lines = state.lines.filter((l) => l.productId !== action.productId);
      return withChange(
        state,
        { lines, shippingAddressId: lines.length > 0 ? state.shippingAddressId : null },
        action.mint,
      );
    }
    case 'setAddress':
      if (action.addressId === state.shippingAddressId) return state;
      return withChange(state, { shippingAddressId: action.addressId }, action.mint);
    case 'acceptPrices': {
      const lines = state.lines.map((l) => {
        const price = action.prices[l.productId];
        return price === undefined ? l : { ...l, priceAtAdd: price };
      });
      return withChange(state, { lines }, action.mint);
    }
    case 'enterCheckout':
      return {
        ...state,
        checkoutKey: nextCheckoutKey(
          state.checkoutKey,
          'checkoutEntered',
          state.lines.length > 0,
          action.mint,
        ),
      };
    case 'startOver':
      return {
        ...state,
        checkoutKey: nextCheckoutKey(
          state.checkoutKey,
          'startOver',
          state.lines.length > 0,
          action.mint,
        ),
      };
    case 'orderCreated':
      return { ...EMPTY_CART, ownerUserId: state.ownerUserId };
    case 'session':
      if (state.ownerUserId === null) return { ...state, ownerUserId: action.userId };
      // Somebody else's cart on a shared machine. Dropping it is the point of recording an
      // owner at all -- it catches the case where a logout's clear did not happen.
      return state.ownerUserId === action.userId
        ? state
        : { ...EMPTY_CART, ownerUserId: action.userId };
    case 'clear':
      return EMPTY_CART;
  }
}

/** Client-side estimate. Labelled as such wherever it is shown. */
export function estimatedTotal(lines: readonly CartLine[]): number {
  return lines.reduce((sum, l) => sum + l.priceAtAdd * l.quantity, 0);
}

export function itemCount(lines: readonly CartLine[]): number {
  return lines.reduce((n, l) => n + l.quantity, 0);
}

/* -------------------------------------------------------------------------------------- */
/* Persistence                                                                             */
/* -------------------------------------------------------------------------------------- */

const storedCartSchema = z.object({
  version: z.literal(1),
  ownerUserId: z.string().nullable(),
  lines: z.array(
    z.object({
      productId: z.string(),
      sku: z.string(),
      nameAtAdd: z.string(),
      priceAtAdd: z.number(),
      quantity: z.number().int().min(MIN_QUANTITY).max(MAX_QUANTITY),
    }),
  ),
  shippingAddressId: z.string().nullable(),
  checkoutKey: z.string().nullable(),
});

/**
 * Read the stored cart. Anything unreadable -- another version, a hand-edited value, a quota
 * error, storage disabled -- is an EMPTY cart, never an exception: a broken localStorage entry
 * must not be able to stop the app rendering.
 */
export function loadCart(storage: Pick<Storage, 'getItem'> | undefined): CartState {
  try {
    const raw = storage?.getItem(CART_STORAGE_KEY);
    if (!raw) return EMPTY_CART;
    const parsed = storedCartSchema.safeParse(JSON.parse(raw));
    return parsed.success ? parsed.data : EMPTY_CART;
  } catch {
    return EMPTY_CART;
  }
}

export function saveCart(
  storage: Pick<Storage, 'setItem' | 'removeItem'> | undefined,
  state: CartState,
): void {
  try {
    if (
      state.lines.length === 0 &&
      state.checkoutKey === null &&
      state.shippingAddressId === null
    ) {
      storage?.removeItem(CART_STORAGE_KEY);
    } else {
      storage?.setItem(CART_STORAGE_KEY, JSON.stringify(state));
    }
  } catch {
    // Private mode or a full quota. The cart still works for this tab; it just will not
    // survive a reload, which is the same as it would be with no storage at all.
  }
}
