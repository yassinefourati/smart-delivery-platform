/**
 * The query keys, and the one property that makes them worth a file of their own.
 *
 * THE SCOPING RULE: a key includes the identity the server scopes on. The concrete bug it prevents
 * is a cache entry shared across a login boundary -- `GET /api/v1/orders/user/{userId}` is
 * authorised with `#userId.toString() == authentication.name or hasRole('ADMIN')`, so the same URL
 * returns different data per session. A key without `userId` means the second user to use the tab
 * sees the first user's orders from cache while the refetch is in flight, and no server-side check
 * can catch it because no request is made.
 */

import { describe, expect, it } from 'vitest';
import { mutationKeys, queryKeys } from '../queryKeys';

const USER_A = 'aaaaaaaa-0000-0000-0000-000000000001';
const USER_B = 'bbbbbbbb-0000-0000-0000-000000000002';

describe('the scoping rule', () => {
  it('puts the user id in every user-scoped key', () => {
    expect(queryKeys.orders.byUser(USER_A)).toContain(USER_A);
    expect(queryKeys.orders.byUserPage(USER_A, { page: 0 })).toContain(USER_A);
    expect(queryKeys.account.profile(USER_A)).toContain(USER_A);
    expect(queryKeys.account.addresses(USER_A)).toContain(USER_A);
    expect(queryKeys.deliveries.byAgentUser(USER_A)).toContain(USER_A);
  });

  it('gives two users different keys for the same page of orders', () => {
    const a = queryKeys.orders.byUserPage(USER_A, { page: 0, size: 20 });
    const b = queryKeys.orders.byUserPage(USER_B, { page: 0, size: 20 });
    expect(a).not.toEqual(b);
  });

  it('nests every user-scoped orders key under the same invalidation prefix', () => {
    // After placing or cancelling, one `invalidateQueries({ queryKey: byUser(userId) })` has to
    // reach every page. That only works if the page key EXTENDS the prefix.
    const prefix = queryKeys.orders.byUser(USER_A);
    const page = queryKeys.orders.byUserPage(USER_A, { page: 3 });
    expect(page.slice(0, prefix.length)).toEqual([...prefix]);
  });

  it('nests catalog, inventory and dispatch keys under their own prefixes too', () => {
    expect(queryKeys.catalog.productList({ page: 0 }).slice(0, 2)).toEqual([
      ...queryKeys.catalog.products(),
    ]);
    expect(queryKeys.catalog.product('p1').slice(0, 1)).toEqual([...queryKeys.catalog.all()]);
    expect(queryKeys.inventory.summary('p1').slice(0, 1)).toEqual([...queryKeys.inventory.all()]);
    expect(queryKeys.dispatch.shipment('s1').slice(0, 2)).toEqual([
      ...queryKeys.dispatch.shipments(),
    ]);
  });
});

describe('stability and distinctness', () => {
  it('is stable for the same arguments, so an invalidation matches its query', () => {
    // The failure this prevents is silent: an invalidation written in one file and a query written
    // in another, differing by one element, so the invalidation does nothing and the screen stays
    // stale with no error anywhere.
    expect(queryKeys.orders.status('order-1')).toEqual(queryKeys.orders.status('order-1'));
    expect(queryKeys.catalog.productList({ search: 'widget', page: 0 })).toEqual(
      queryKeys.catalog.productList({ search: 'widget', page: 0 }),
    );
  });

  it('separates the polled status key from the order detail key', () => {
    // Two entries on purpose: the status query is polled on an interval and the detail query is
    // refetched once per observed transition. One key would poll the whole order.
    expect(queryKeys.orders.status('order-1')).not.toEqual(queryKeys.orders.detail('order-1'));
  });

  it('separates different ids and different filters', () => {
    expect(queryKeys.catalog.product('p1')).not.toEqual(queryKeys.catalog.product('p2'));
    expect(queryKeys.catalog.productList({ page: 0 })).not.toEqual(
      queryKeys.catalog.productList({ page: 1 }),
    );
    expect(queryKeys.inventory.summary('p1')).not.toEqual(queryKeys.inventory.summary('p2'));
  });

  it('is JSON-serialisable, which is what TanStack hashes', () => {
    const key = queryKeys.catalog.productList({ search: 'x', minPrice: 1, page: 0 });
    expect(JSON.parse(JSON.stringify(key))).toEqual([...key]);
  });
});

describe('every factory', () => {
  /**
   * Call all of them once.
   *
   * Not ceremony: a key factory is called from a feature stage months from now, and the failure
   * mode of a typo in one is a cache entry nothing reads and an invalidation that does nothing --
   * silent in both directions, with no error anywhere. Walking the whole set is the cheapest way to
   * prove each one returns a non-empty, distinct, serialisable tuple.
   */
  const built: Record<string, readonly unknown[]> = {
    'catalog.all': queryKeys.catalog.all(),
    'catalog.products': queryKeys.catalog.products(),
    'catalog.productList': queryKeys.catalog.productList({ search: 'widget' }),
    'catalog.product': queryKeys.catalog.product('p1'),
    'catalog.categories': queryKeys.catalog.categories(),
    'catalog.category': queryKeys.catalog.category('c1'),
    'inventory.all': queryKeys.inventory.all(),
    'inventory.summary': queryKeys.inventory.summary('p1'),
    'inventory.warehouses': queryKeys.inventory.warehouses(),
    'inventory.warehouse': queryKeys.inventory.warehouse('w1'),
    'orders.all': queryKeys.orders.all(),
    'orders.byUser': queryKeys.orders.byUser(USER_A),
    'orders.byUserPage': queryKeys.orders.byUserPage(USER_A, { page: 0 }),
    'orders.detail': queryKeys.orders.detail('o1'),
    'orders.status': queryKeys.orders.status('o1'),
    'account.all': queryKeys.account.all(),
    'account.profile': queryKeys.account.profile(USER_A),
    'account.addresses': queryKeys.account.addresses(USER_A),
    'dispatch.all': queryKeys.dispatch.all(),
    'dispatch.shipments': queryKeys.dispatch.shipments(),
    'dispatch.shipment': queryKeys.dispatch.shipment('s1'),
    'dispatch.shipmentByOrder': queryKeys.dispatch.shipmentByOrder('o1'),
    'dispatch.paymentByOrder': queryKeys.dispatch.paymentByOrder('o1'),
    'dispatch.payment': queryKeys.dispatch.payment('pay1'),
    'dispatch.agents': queryKeys.dispatch.agents(),
    'dispatch.agent': queryKeys.dispatch.agent('a1'),
    'deliveries.all': queryKeys.deliveries.all(),
    'deliveries.byAgentUser': queryKeys.deliveries.byAgentUser(USER_A),
    'deliveries.detail': queryKeys.deliveries.detail('d1'),
  };

  it.each(Object.entries(built))('%s returns a usable tuple', (_name, key) => {
    expect(Array.isArray(key)).toBe(true);
    expect(key.length).toBeGreaterThan(0);
    expect(JSON.parse(JSON.stringify(key))).toEqual([...key]);
  });

  it('gives no two factories the same key', () => {
    const serialised = Object.values(built).map((key) => JSON.stringify(key));
    expect(new Set(serialised).size).toBe(serialised.length);
  });

  it('exposes exactly the six groups the app has', () => {
    // A seventh group means a new backend service or a new persona, and both are decisions worth
    // noticing in a diff rather than discovering in a cache.
    expect(Object.keys(queryKeys).sort()).toEqual([
      'account',
      'catalog',
      'deliveries',
      'dispatch',
      'inventory',
      'orders',
    ]);
  });
});

describe('mutation keys', () => {
  it('keys placeOrder by the persisted checkout key', () => {
    // So a second mount of /checkout with the same key shares the in-flight mutation rather than
    // starting a second one. The belt; the persisted Idempotency-Key is the braces.
    expect(mutationKeys.placeOrder('key-1')).toEqual(['placeOrder', 'key-1']);
    expect(mutationKeys.placeOrder('key-1')).not.toEqual(mutationKeys.placeOrder('key-2'));
  });

  it('keys cancelOrder by the order', () => {
    expect(mutationKeys.cancelOrder('order-1')).toEqual(['cancelOrder', 'order-1']);
  });
});
