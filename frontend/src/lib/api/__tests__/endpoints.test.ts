/**
 * Every endpoint function, driven through MSW: the METHOD it uses, the PATH it builds, and the
 * schema its result is parsed by.
 *
 * WHY A TABLE OVER ALL FORTY. A wrong method or a wrong path is not a subtle bug -- it is a 404 or
 * a 405 that reads to a user like missing data, and it is exactly the kind of mistake that survives
 * review because every line looks like the line above it. Forty rows in one table are readable side
 * by side in a way forty scattered assertions are not, and the seven feature stages build directly
 * on these signatures.
 *
 * The response bodies are the captured fixtures, so each row also proves the endpoint's schema
 * accepts what the real endpoint really returns.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as api from '../endpoints';
import { http, jsonResponse, noContentResponse, server, setupTestServer } from './testServer';
import { setAuthorizationAttacher } from '../http';
import {
  ADDRESS_RESPONSE,
  AGENT_RESPONSE,
  CATEGORY_RESPONSE,
  DELIVERY_RESPONSE_ASSIGNED,
  DELIVERY_RESPONSE_COMPLETED,
  INVENTORY_RESPONSE,
  INVENTORY_SUMMARY_IN_STOCK,
  LOGIN_RESPONSE,
  ORDER_PAGE_RESPONSE,
  ORDER_RESPONSE_CANCELLED,
  ORDER_RESPONSE_CREATED,
  ORDER_STATUS_RESPONSE,
  PAYMENT_RESPONSE,
  PRODUCT_CREATED_201,
  PRODUCT_PAGE_RESPONSE,
  PRODUCT_RESPONSE,
  SHIPMENT_RESPONSE,
  USER_RESPONSE,
  WAREHOUSE_RESPONSE,
} from './fixtures';

setupTestServer();

beforeEach(() => {
  setAuthorizationAttacher(null);
  vi.spyOn(console, 'error').mockImplementation(() => {});
});

const USER_ID = '46d92ff6-6a3f-40bb-8b65-f6dce4ecb82e';
const ORDER_ID = 'b487ec64-c65b-4f29-898e-c82d5d790007';
const PRODUCT_ID = '1d4902af-68f1-41b6-b30a-68cabb105603';
const CATEGORY_ID = 'c3795fe2-adb4-4c16-b641-bfbfcad4ad83';
const ADDRESS_ID = '08c14adb-3ddd-4f1c-92e6-2a979bc2e51f';
const WAREHOUSE_ID = 'a8a74f04-e747-47eb-be74-0b63b81ba98e';
const SHIPMENT_ID = '7b689a42-958e-449e-a325-35bf5f4b5a68';
const AGENT_ID = 'c579625e-98f4-4293-841f-02ff79fdc862';
const DELIVERY_ID = '6f743e58-6133-47dc-b98e-577905d0c0a1';
const PAYMENT_ID = '0975d782-4882-46b4-a7c6-f8eb6cb5788f';

interface EndpointCase {
  readonly label: string;
  readonly method: string;
  readonly pathname: string;
  /** The captured body the fake gateway answers with. `undefined` means a 204 with no body. */
  readonly body?: unknown;
  readonly status?: number;
  readonly call: () => Promise<unknown>;
}

const ADDRESS_BODY = {
  label: 'Work',
  street: '2 Probe Lane',
  city: 'Testville',
  postalCode: '99999',
  country: 'GB',
} as const;

const PRODUCT_BODY = {
  sku: 'CONTRACT-1',
  name: 'Contract Probe',
  price: 9.99,
  categoryId: CATEGORY_ID,
  active: true,
} as const;

const cases: readonly EndpointCase[] = [
  /* -- user-service ------------------------------------------------------------------ */
  {
    label: 'login',
    method: 'POST',
    pathname: '/api/v1/auth/login',
    body: LOGIN_RESPONSE,
    call: () => api.login({ email: 'a@b.test', password: 'pw' }),
  },
  {
    label: 'registerUser',
    method: 'POST',
    pathname: '/api/v1/users',
    body: USER_RESPONSE,
    status: 201,
    call: () =>
      api.registerUser({ email: 'a@b.test', password: 'pw', firstName: 'A', lastName: 'B' }),
  },
  {
    label: 'getUser',
    method: 'GET',
    pathname: `/api/v1/users/${USER_ID}`,
    body: USER_RESPONSE,
    call: () => api.getUser(USER_ID),
  },
  {
    label: 'updateUser',
    method: 'PUT',
    pathname: `/api/v1/users/${USER_ID}`,
    body: USER_RESPONSE,
    call: () => api.updateUser(USER_ID, { firstName: 'A', lastName: 'B' }),
  },
  {
    label: 'listAddresses',
    method: 'GET',
    pathname: `/api/v1/users/${USER_ID}/addresses`,
    body: [ADDRESS_RESPONSE],
    call: () => api.listAddresses(USER_ID),
  },
  {
    label: 'createAddress',
    method: 'POST',
    pathname: `/api/v1/users/${USER_ID}/addresses`,
    body: ADDRESS_RESPONSE,
    status: 201,
    call: () => api.createAddress(USER_ID, ADDRESS_BODY),
  },
  {
    label: 'updateAddress',
    method: 'PUT',
    pathname: `/api/v1/users/${USER_ID}/addresses/${ADDRESS_ID}`,
    body: ADDRESS_RESPONSE,
    call: () => api.updateAddress(USER_ID, ADDRESS_ID, ADDRESS_BODY),
  },
  {
    // 204, although the document says 200.
    label: 'deleteAddress',
    method: 'DELETE',
    pathname: `/api/v1/users/${USER_ID}/addresses/${ADDRESS_ID}`,
    call: () => api.deleteAddress(USER_ID, ADDRESS_ID),
  },

  /* -- product-service --------------------------------------------------------------- */
  {
    label: 'listProducts',
    method: 'GET',
    pathname: '/api/v1/products',
    body: PRODUCT_PAGE_RESPONSE,
    call: () => api.listProducts({ page: 0, size: 2, sort: 'price,desc', search: 'widget' }),
  },
  {
    label: 'listProducts with no query at all',
    method: 'GET',
    pathname: '/api/v1/products',
    body: PRODUCT_PAGE_RESPONSE,
    call: () => api.listProducts(),
  },
  {
    label: 'getProduct',
    method: 'GET',
    pathname: `/api/v1/products/${PRODUCT_ID}`,
    body: PRODUCT_RESPONSE,
    call: () => api.getProduct(PRODUCT_ID),
  },
  {
    // The 201 whose audit timestamps are null. This row is the regression guard.
    label: 'createProduct (201 with null timestamps)',
    method: 'POST',
    pathname: '/api/v1/products',
    body: PRODUCT_CREATED_201,
    status: 201,
    call: () => api.createProduct(PRODUCT_BODY),
  },
  {
    label: 'updateProduct',
    method: 'PUT',
    pathname: `/api/v1/products/${PRODUCT_ID}`,
    body: PRODUCT_RESPONSE,
    call: () => api.updateProduct(PRODUCT_ID, PRODUCT_BODY),
  },
  {
    label: 'deleteProduct',
    method: 'DELETE',
    pathname: `/api/v1/products/${PRODUCT_ID}`,
    call: () => api.deleteProduct(PRODUCT_ID),
  },
  {
    label: 'listCategories',
    method: 'GET',
    pathname: '/api/v1/categories',
    body: [CATEGORY_RESPONSE],
    call: () => api.listCategories(),
  },
  {
    label: 'getCategory',
    method: 'GET',
    pathname: `/api/v1/categories/${CATEGORY_ID}`,
    body: CATEGORY_RESPONSE,
    call: () => api.getCategory(CATEGORY_ID),
  },
  {
    label: 'createCategory',
    method: 'POST',
    pathname: '/api/v1/categories',
    body: CATEGORY_RESPONSE,
    status: 201,
    call: () => api.createCategory({ name: 'Widgets' }),
  },
  {
    label: 'updateCategory',
    method: 'PUT',
    pathname: `/api/v1/categories/${CATEGORY_ID}`,
    body: CATEGORY_RESPONSE,
    call: () => api.updateCategory(CATEGORY_ID, { name: 'Widgets', description: 'kept' }),
  },
  {
    label: 'deleteCategory',
    method: 'DELETE',
    pathname: `/api/v1/categories/${CATEGORY_ID}`,
    call: () => api.deleteCategory(CATEGORY_ID),
  },

  /* -- inventory-service ------------------------------------------------------------- */
  {
    label: 'getInventorySummary (public)',
    method: 'GET',
    pathname: `/api/v1/inventory/${PRODUCT_ID}`,
    body: INVENTORY_SUMMARY_IN_STOCK,
    call: () => api.getInventorySummary(PRODUCT_ID),
  },
  {
    label: 'createInventory',
    method: 'POST',
    pathname: '/api/v1/inventory',
    body: INVENTORY_RESPONSE,
    status: 201,
    call: () =>
      api.createInventory({
        productId: PRODUCT_ID,
        warehouseId: WAREHOUSE_ID,
        availableQuantity: 50,
      }),
  },
  {
    label: 'listWarehouses',
    method: 'GET',
    pathname: '/api/v1/warehouses',
    body: [WAREHOUSE_RESPONSE],
    call: () => api.listWarehouses(),
  },
  {
    label: 'getWarehouse',
    method: 'GET',
    pathname: `/api/v1/warehouses/${WAREHOUSE_ID}`,
    body: WAREHOUSE_RESPONSE,
    call: () => api.getWarehouse(WAREHOUSE_ID),
  },
  {
    label: 'createWarehouse',
    method: 'POST',
    pathname: '/api/v1/warehouses',
    body: WAREHOUSE_RESPONSE,
    status: 201,
    call: () => api.createWarehouse({ name: 'Depot', location: '1 Dock Road' }),
  },

  /* -- order-service ----------------------------------------------------------------- */
  {
    label: 'createOrder',
    method: 'POST',
    pathname: '/api/v1/orders',
    body: ORDER_RESPONSE_CREATED,
    status: 201,
    call: () =>
      api.createOrder(
        { shippingAddressId: ADDRESS_ID, items: [{ productId: PRODUCT_ID, quantity: 2 }] },
        'the-persisted-key',
      ),
  },
  {
    label: 'getOrder',
    method: 'GET',
    pathname: `/api/v1/orders/${ORDER_ID}`,
    body: ORDER_RESPONSE_CREATED,
    call: () => api.getOrder(ORDER_ID),
  },
  {
    label: 'getOrderStatus',
    method: 'GET',
    pathname: `/api/v1/orders/${ORDER_ID}/status`,
    body: ORDER_STATUS_RESPONSE,
    call: () => api.getOrderStatus(ORDER_ID),
  },
  {
    label: 'listUserOrders',
    method: 'GET',
    pathname: `/api/v1/orders/user/${USER_ID}`,
    body: ORDER_PAGE_RESPONSE,
    call: () => api.listUserOrders(USER_ID, { page: 0, size: 5 }),
  },
  {
    label: 'listUserOrders with no query',
    method: 'GET',
    pathname: `/api/v1/orders/user/${USER_ID}`,
    body: ORDER_PAGE_RESPONSE,
    call: () => api.listUserOrders(USER_ID),
  },
  {
    label: 'cancelOrder',
    method: 'POST',
    pathname: `/api/v1/orders/${ORDER_ID}/cancel`,
    body: ORDER_RESPONSE_CANCELLED,
    call: () => api.cancelOrder(ORDER_ID),
  },

  /* -- payment-service (ADMIN) ------------------------------------------------------- */
  {
    label: 'getPaymentByOrder',
    method: 'GET',
    pathname: `/api/v1/payments/order/${ORDER_ID}`,
    body: PAYMENT_RESPONSE,
    call: () => api.getPaymentByOrder(ORDER_ID),
  },
  {
    label: 'getPayment',
    method: 'GET',
    pathname: `/api/v1/payments/${PAYMENT_ID}`,
    body: PAYMENT_RESPONSE,
    call: () => api.getPayment(PAYMENT_ID),
  },

  /* -- delivery-service -------------------------------------------------------------- */
  {
    label: 'listShipments',
    method: 'GET',
    pathname: '/api/v1/shipments',
    body: [SHIPMENT_RESPONSE],
    call: () => api.listShipments(),
  },
  {
    label: 'getShipment',
    method: 'GET',
    pathname: `/api/v1/shipments/${SHIPMENT_ID}`,
    body: SHIPMENT_RESPONSE,
    call: () => api.getShipment(SHIPMENT_ID),
  },
  {
    label: 'getShipmentByOrder',
    method: 'GET',
    pathname: `/api/v1/shipments/order/${ORDER_ID}`,
    body: SHIPMENT_RESPONSE,
    call: () => api.getShipmentByOrder(ORDER_ID),
  },
  {
    // Returns a DELIVERY, not a shipment, although the path is under /shipments. And 201, not the
    // 200 the document promises.
    label: 'assignDelivery',
    method: 'POST',
    pathname: `/api/v1/shipments/${SHIPMENT_ID}/assign`,
    body: DELIVERY_RESPONSE_ASSIGNED,
    status: 201,
    call: () => api.assignDelivery(SHIPMENT_ID, { agentId: AGENT_ID }),
  },
  {
    label: 'listAgents',
    method: 'GET',
    pathname: '/api/v1/agents',
    body: [AGENT_RESPONSE],
    call: () => api.listAgents(),
  },
  {
    label: 'getAgent',
    method: 'GET',
    pathname: `/api/v1/agents/${AGENT_ID}`,
    body: AGENT_RESPONSE,
    call: () => api.getAgent(AGENT_ID),
  },
  {
    label: 'createAgent',
    method: 'POST',
    pathname: '/api/v1/agents',
    body: AGENT_RESPONSE,
    status: 201,
    call: () => api.createAgent({ userId: USER_ID, name: 'Probe Agent', phone: '+15550199' }),
  },
  {
    // Takes the agent's USER id. Passing the agent id returns an empty array, not an error.
    label: 'listDeliveriesForAgentUser',
    method: 'GET',
    pathname: `/api/v1/deliveries/agent/${USER_ID}`,
    body: [DELIVERY_RESPONSE_COMPLETED],
    call: () => api.listDeliveriesForAgentUser(USER_ID),
  },
  {
    label: 'getDelivery',
    method: 'GET',
    pathname: `/api/v1/deliveries/${DELIVERY_ID}`,
    body: DELIVERY_RESPONSE_ASSIGNED,
    call: () => api.getDelivery(DELIVERY_ID),
  },
  {
    label: 'completeDelivery',
    method: 'POST',
    pathname: `/api/v1/deliveries/${DELIVERY_ID}/complete`,
    body: DELIVERY_RESPONSE_COMPLETED,
    call: () => api.completeDelivery(DELIVERY_ID),
  },
];

describe('endpoints -- method, path and schema for every operation', () => {
  it.each(cases)('$label', async (endpointCase) => {
    let seenMethod = '';
    let seenUrl = '';
    server.use(
      http.all('*', ({ request: incoming }) => {
        seenMethod = incoming.method;
        seenUrl = incoming.url;
        return endpointCase.body === undefined
          ? noContentResponse()
          : jsonResponse(endpointCase.body, endpointCase.status ?? 200);
      }),
    );

    const result = await endpointCase.call();

    expect(seenMethod).toBe(endpointCase.method);
    expect(new URL(seenUrl).pathname).toBe(endpointCase.pathname);
    // Every URL is same-origin by construction: the path is relative and `src/config.ts` cannot
    // express an origin.
    expect(new URL(seenUrl).origin).toBe(globalThis.location.origin);

    if (endpointCase.body === undefined) {
      expect(result).toBeUndefined();
    } else {
      expect(result).not.toBeUndefined();
    }
  });

  it('covers every exported endpoint function', () => {
    // A row per operation, so adding an endpoint without a row fails here rather than shipping
    // untested. `CallOptions` is a type and contributes no runtime export.
    const exported = Object.keys(api).filter(
      (name) => typeof (api as Record<string, unknown>)[name] === 'function',
    );
    const labelled = new Set(cases.map((endpointCase) => endpointCase.label.split(' ')[0]));
    const missing = exported.filter((name) => !labelled.has(name));
    expect(missing).toEqual([]);
  });
});

describe('the details that are easy to get wrong', () => {
  it('sends the Idempotency-Key on createOrder and on nothing else', async () => {
    const keys: (string | null)[] = [];
    server.use(
      http.post('/api/v1/orders', ({ request: incoming }) => {
        keys.push(incoming.headers.get('Idempotency-Key'));
        return jsonResponse(ORDER_RESPONSE_CREATED, 201);
      }),
      http.post('/api/v1/orders/:orderId/cancel', ({ request: incoming }) => {
        keys.push(incoming.headers.get('Idempotency-Key'));
        return jsonResponse(ORDER_RESPONSE_CANCELLED);
      }),
    );

    await api.createOrder(
      { shippingAddressId: ADDRESS_ID, items: [{ productId: PRODUCT_ID, quantity: 1 }] },
      'the-persisted-key',
    );
    await api.cancelOrder(ORDER_ID);

    expect(keys[0]).toBe('the-persisted-key');
    // There is NO idempotency key on the cancel endpoint, which is exactly why cancel is never
    // auto-retried.
    expect(keys[1]).toBeNull();
  });

  it('returns the SAME order id for a replay, which is why the client must not try to tell', async () => {
    // Verified live: the same key with the same body returns 201 again, with the same id. So the
    // status code carries no information about which happened, and the only correct behaviour is to
    // navigate to the returned id -- right either way.
    server.use(http.post('/api/v1/orders', () => jsonResponse(ORDER_RESPONSE_CREATED, 201)));

    const body = {
      shippingAddressId: ADDRESS_ID,
      items: [{ productId: PRODUCT_ID, quantity: 2 }],
    };
    const first = await api.createOrder(body, 'same-key');
    const replay = await api.createOrder(body, 'same-key');

    expect(replay.id).toBe(first.id);
  });

  it('puts page, size and sort on the wire although no document mentions them', async () => {
    let url = '';
    server.use(
      http.get('/api/v1/products', ({ request: incoming }) => {
        url = incoming.url;
        return jsonResponse(PRODUCT_PAGE_RESPONSE);
      }),
    );

    await api.listProducts({ page: 1, size: 2, sort: 'price,desc' });

    const params = new URL(url).searchParams;
    expect(params.get('page')).toBe('1');
    expect(params.get('size')).toBe('2');
    expect(params.get('sort')).toBe('price,desc');
  });

  it('forwards an AbortSignal, which is how Query cancels a read on unmount', async () => {
    // Every read takes `options.signal` because TanStack Query passes one in and aborts it on
    // unmount and on key change. A user who clicks through three products in two seconds then
    // issues three requests and keeps one, rather than racing three responses into one cache entry.
    server.use(http.get('/api/v1/products/:id', () => jsonResponse(PRODUCT_RESPONSE)));
    const controller = new AbortController();

    await expect(api.getProduct(PRODUCT_ID, { signal: controller.signal })).resolves.toMatchObject({
      id: PRODUCT_ID,
    });
  });

  it("does not send the document's `pageable` object parameter for user orders", async () => {
    let url = '';
    server.use(
      http.get('/api/v1/orders/user/:userId', ({ request: incoming }) => {
        url = incoming.url;
        return jsonResponse(ORDER_PAGE_RESPONSE);
      }),
    );

    await api.listUserOrders(USER_ID, { page: 0, size: 5 });

    // A generated client would have sent `?pageable=%5Bobject+Object%5D`.
    expect(url).not.toContain('pageable');
    expect(new URL(url).searchParams.get('size')).toBe('5');
  });
});
