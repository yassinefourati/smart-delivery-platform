import {
  ADDRESS_RESPONSE,
  INVENTORY_SUMMARY_IN_STOCK,
  ORDER_RESPONSE_CREATED,
  PRODUCT_RESPONSE,
} from '../lib/api/__tests__/fixtures';
import { http, jsonResponse } from '../lib/api/__tests__/testServer';
import { CART_STORAGE_KEY, type CartState } from '../lib/cart/cart';

/** Fixtures captured from the running gateway, re-dated to now so polling clocks start fresh. */
export const product = PRODUCT_RESPONSE;
export const address = { ...ADDRESS_RESPONSE, isDefault: true };
export const otherAddress = {
  ...ADDRESS_RESPONSE,
  id: '5b0e2d8e-8d5d-4d53-9a8e-1b5f8b0c0f11',
  label: 'Home',
  isDefault: false,
};

export function freshOrder(status: string, overrides: Record<string, unknown> = {}) {
  const now = new Date().toISOString();
  return { ...ORDER_RESPONSE_CREATED, status, createdAt: now, updatedAt: now, ...overrides };
}

/** The reads every checkout-adjacent screen makes. */
export const catalogReads = [
  http.get('/api/v1/products/:productId', () => jsonResponse(product)),
  http.get('/api/v1/inventory/:productId', () => jsonResponse(INVENTORY_SUMMARY_IN_STOCK)),
  http.get('/api/v1/categories', () => jsonResponse([])),
];

/** Put a cart in localStorage exactly as CartProvider would have persisted it. */
export function seedCart(partial: Partial<CartState> = {}) {
  const cart: CartState = {
    version: 1,
    ownerUserId: null,
    lines: [
      {
        productId: product.id,
        sku: product.sku,
        nameAtAdd: product.name,
        priceAtAdd: product.price,
        quantity: 2,
      },
    ],
    shippingAddressId: null,
    checkoutKey: null,
    ...partial,
  };
  localStorage.setItem(CART_STORAGE_KEY, JSON.stringify(cart));
}

/** The persisted cart. An empty cart is removed from storage, which reads back as an empty cart. */
export function storedCart(): CartState {
  const raw = localStorage.getItem(CART_STORAGE_KEY);
  return raw === null
    ? { version: 1, ownerUserId: null, lines: [], shippingAddressId: null, checkoutKey: null }
    : (JSON.parse(raw) as CartState);
}
