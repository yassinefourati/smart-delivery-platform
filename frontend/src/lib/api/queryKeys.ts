/**
 * Every TanStack Query key in the application, as tuple factories.
 *
 * THE RULE THIS FILE EXISTS TO ENFORCE: a key ALWAYS includes the identity the server scopes
 * on. `['orders', 'byUser', userId, page]`, never `['orders', page]`.
 *
 * THE BUG PREVENTED, concretely: order-service authorises `GET /api/v1/orders/user/{userId}` with
 * `#userId.toString() == authentication.name or hasRole('ADMIN')`, so the same URL returns
 * different data for different sessions. A key that omits `userId` is one cache entry shared by
 * every account that has used the tab -- so after a logout and a second login, the new user
 * sees the previous user's orders from cache while the refetch is in flight. On a shared machine
 * that is a data leak that no server-side check can catch, because the request is never made.
 *
 * Nothing in this project builds a key inline. The reason is not tidiness: an invalidation and
 * the query it is meant to invalidate are written in two different files, months apart, and a
 * one-element difference between them is an invalidation that silently does nothing. Factories
 * make that a compile error instead of a stale screen.
 *
 * ON THE PLAN'S ILLUSTRATIVE TUPLES: the brief writes `['order', id]` and `['order-status', id]`
 * in prose. The factories below are the authoritative spelling (`['orders','detail',id]` and
 * `['orders','status',id]`); the prose was illustrating the shape, not fixing the strings. Use
 * the factories, including for `queryClient.setQueryData` after the 201 -- a literal tuple that
 * differs by one character seeds a cache entry nothing will ever read.
 */

import type { ProductListQuery } from './schemas/product';
import type { OrderListQuery } from './schemas/order';

/**
 * Catalog. `categories` needs no scoping identity -- `GET /api/v1/categories` is `permitAll()`
 * and returns the same list to everyone, authenticated or not.
 */
const catalog = {
  /** Prefix for "everything catalog". Use for a blanket invalidation after an admin write. */
  all: () => ['catalog'] as const,
  products: () => ['catalog', 'products'] as const,
  /**
   * The filter object is part of the key. TanStack hashes object keys in a stable order, so
   * `{page: 0, search: 'x'}` and `{search: 'x', page: 0}` are the same entry -- which is what
   * you want, since the catalog's filters arrive from `useSearchParams` in whatever order the
   * URL happened to carry them.
   */
  productList: (query: ProductListQuery) => ['catalog', 'products', 'list', query] as const,
  product: (productId: string) => ['catalog', 'products', 'detail', productId] as const,
  categories: () => ['catalog', 'categories'] as const,
  category: (categoryId: string) => ['catalog', 'categories', 'detail', categoryId] as const,
} as const;

/**
 * Stock. Scoped by `productId`, which is the only identity the public summary endpoint takes.
 *
 * `GET /api/v1/inventory/{productId}` is `permitAll()` (verified: 200 with no token), so this
 * cache is safe to share across a login boundary -- it holds nothing user-specific. It is the
 * one cache in this file where that is true, and saying so is cheaper than someone later
 * wondering whether `userId` was forgotten.
 */
const inventory = {
  all: () => ['inventory'] as const,
  summary: (productId: string) => ['inventory', 'summary', productId] as const,
  warehouses: () => ['inventory', 'warehouses'] as const,
  warehouse: (warehouseId: string) => ['inventory', 'warehouses', 'detail', warehouseId] as const,
} as const;

/**
 * Orders. The one place the scoping rule is load-bearing.
 *
 * `detail` and `status` are keyed by the ORDER id and not additionally by `userId`. That is a
 * considered exception, not an oversight: an order id is an unguessable server-assigned
 * identifier, the server enforces ownership on every read of it, and a second session cannot
 * arrive holding another user's order id without having been given it. The LIST is the leak
 * surface, because its URL is derived from a user id the app already knows, and that is the one
 * keyed by `userId`.
 */
const orders = {
  all: () => ['orders'] as const,
  /** Every page of one user's orders. The prefix to invalidate after placing or cancelling. */
  byUser: (userId: string) => ['orders', 'byUser', userId] as const,
  byUserPage: (userId: string, query: OrderListQuery) =>
    ['orders', 'byUser', userId, query] as const,
  detail: (orderId: string) => ['orders', 'detail', orderId] as const,
  /**
   * The polled key. `refetchInterval` is a function of this query's own latest data
   * (`nextPollDelay(q.state.data?.status, ...)`), which is why the status endpoint gets its own
   * entry rather than being read off the order detail.
   */
  status: (orderId: string) => ['orders', 'status', orderId] as const,
} as const;

/** The signed-in user's own record and addresses. Both scoped by `userId`, both owner-or-admin. */
const account = {
  all: () => ['account'] as const,
  profile: (userId: string) => ['account', 'profile', userId] as const,
  addresses: (userId: string) => ['account', 'addresses', userId] as const,
} as const;

/**
 * Admin dispatch: shipments, payments, agents.
 *
 * `/api/v1/shipments/**` and `/api/v1/payments/**` are ADMIN-only (verified: a customer token
 * gets 403 on both), so these entries only ever exist in an admin session. They still carry no
 * `userId`, because the data is not user-scoped at all -- an admin sees the same shipments as
 * any other admin.
 */
const dispatch = {
  all: () => ['dispatch'] as const,
  shipments: () => ['dispatch', 'shipments'] as const,
  shipment: (shipmentId: string) => ['dispatch', 'shipments', 'detail', shipmentId] as const,
  shipmentByOrder: (orderId: string) => ['dispatch', 'shipments', 'byOrder', orderId] as const,
  paymentByOrder: (orderId: string) => ['dispatch', 'payments', 'byOrder', orderId] as const,
  payment: (paymentId: string) => ['dispatch', 'payments', 'detail', paymentId] as const,
  agents: () => ['dispatch', 'agents'] as const,
  agent: (agentId: string) => ['dispatch', 'agents', 'detail', agentId] as const,
} as const;

/**
 * An agent's own worklist.
 *
 * Keyed by the AGENT'S USER ID, because that is literally what the endpoint takes:
 * `GET /api/v1/deliveries/agent/{userId}`. Passing `AgentResponse.id` there returns an EMPTY
 * ARRAY rather than an error -- the quietest possible failure -- so the parameter name here is
 * `agentUserId` to make the wrong value awkward to pass.
 */
const deliveries = {
  all: () => ['deliveries'] as const,
  byAgentUser: (agentUserId: string) => ['deliveries', 'byAgentUser', agentUserId] as const,
  detail: (deliveryId: string) => ['deliveries', 'detail', deliveryId] as const,
} as const;

export const queryKeys = {
  catalog,
  inventory,
  orders,
  account,
  dispatch,
  deliveries,
} as const;

/**
 * Mutation keys.
 *
 * Only one mutation in this app needs a stable key, and it is the one that must not run twice:
 * `placeOrder` carries `mutationKey: mutationKeys.placeOrder(checkoutKey)` so a second mount
 * of `/checkout` with the same persisted idempotency key shares the in-flight mutation rather
 * than starting a second one.
 *
 * SAID PLAINLY, BECAUSE IT IS EASY TO OVERRATE: this is the belt. The braces are the persisted
 * `Idempotency-Key` itself, which is what makes a double-POST harmless at the server. A
 * mutation key and a disabled button both stop the common case; only the header makes the
 * guarantee.
 */
export const mutationKeys = {
  placeOrder: (checkoutKey: string) => ['placeOrder', checkoutKey] as const,
  cancelOrder: (orderId: string) => ['cancelOrder', orderId] as const,
} as const;
