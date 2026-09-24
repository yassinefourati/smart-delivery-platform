/**
 * ONE TYPED FUNCTION PER API OPERATION. This is the file every feature stage calls.
 *
 * Nothing above this layer knows a URL, a method, a header or a schema. A screen calls
 * `getOrderStatus(orderId, { signal })` and gets an `OrderStatusResponse` or an `ApiProblem`.
 * That is the whole surface.
 *
 * WHY EACH FUNCTION NAMES ITS SCHEMA. The response is parsed at the FETCH BOUNDARY, so a renamed
 * or dropped field fails here with a field path instead of surfacing as `undefined` at render
 * time in a component three levels away. It behaves IDENTICALLY in development and in
 * production: a parser that throws in dev and degrades in prod is how a real break stays hidden
 * until a customer finds it.
 *
 * WHY THERE IS NO GENERATED CLIENT. Five defects, each checked against the six live documents:
 *   1. `GET /api/v1/products`' `page`/`size`/`sort` are `@Parameter(hidden = true)`
 *      (`ProductController:53`) so they are ABSENT from the document, although
 *      `?page=0&size=2&sort=price,desc` works -- a generated client could not paginate the
 *      catalog's primary interaction.
 *   2. `GET /api/v1/orders/user/{userId}` declares `pageable` as a `required: true` OBJECT query
 *      parameter, which serialises as `?pageable=%5Bobject+Object%5D`.
 *   3. `POST /api/v1/orders` documents 200 and returns 201; all four DELETEs document 200 and
 *      return 204.
 *   4. NO response schema in ANY of the six documents carries a `required` array, so every
 *      generated field would be `T | undefined` and the codebase would fill with non-null
 *      assertions asserting something the document never promised.
 *   5. NO operation declares a single error response, so RFC 7807 -- the most important type in
 *      this client -- cannot be generated at all.
 * Codegen would cover the easy half while every genuine hazard stayed manual.
 *
 * WHAT IS DELIBERATELY ABSENT FROM THIS FILE, and it is absent because calling it would be a
 * bug, not because it was forgotten:
 *   - `POST /api/v1/payments` and `POST /api/v1/payments/refund`. The saga charges automatically
 *     after reservation, so a manual charge DOUBLE-CHARGES; compensation issues refunds off the
 *     `order.cancelled` event (ADR 008), so a manual refund RACES the compensation listener and
 *     double-refunds. Reachable to an ADMIN, and still wrong to put behind a button.
 *   - `POST /api/v1/inventory/reserve|release|deduct`. They take an `orderId`, which makes them
 *     the saga's own operations. A hand-released reservation makes an order's state a lie and no
 *     UI could put it back.
 *   - `POST /api/v1/auth/service-token`. A browser holding a service client secret is precisely
 *     the bug that endpoint's per-service-credential design exists to prevent.
 *   - `GET /.well-known/jwks.json`. There is zero JWT code in this browser: `LoginResponse`
 *     already returns `userId`, `roles` and `expiresInSeconds`, and verifying a token
 *     client-side proves nothing because the resource servers verify against the JWKS.
 * The schemas for the first two ARE written (schemas/payment.ts, schemas/inventory.ts), so a
 * future admin tool that genuinely needs them starts from a verified shape rather than a guess.
 */

import { request, requestNoContent } from './http';
import {
  addressListSchema,
  addressResponseSchema,
  loginResponseSchema,
  userResponseSchema,
} from './schemas/user';
import type {
  AddressRequest,
  AddressResponse,
  HumanRole,
  LoginRequest,
  LoginResponse,
  RegisterUserRequest,
  UpdateUserRequest,
  UserResponse,
} from './schemas/user';
import {
  categoryListSchema,
  categoryResponseSchema,
  productPageResponseSchema,
  productResponseSchema,
} from './schemas/product';
import type {
  CategoryRequest,
  CategoryResponse,
  ProductListQuery,
  ProductPageResponse,
  ProductRequest,
  ProductResponse,
} from './schemas/product';
import {
  inventoryResponseSchema,
  inventorySummaryResponseSchema,
  warehouseListSchema,
  warehouseResponseSchema,
} from './schemas/inventory';
import type {
  InventoryCreateRequest,
  InventoryResponse,
  InventorySummaryResponse,
  WarehouseRequest,
  WarehouseResponse,
} from './schemas/inventory';
import {
  orderPageResponseSchema,
  orderResponseSchema,
  orderStatusResponseSchema,
} from './schemas/order';
import type {
  CreateOrderRequest,
  OrderListQuery,
  OrderPageResponse,
  OrderResponse,
  OrderStatusResponse,
} from './schemas/order';
import { paymentResponseSchema } from './schemas/payment';
import type { PaymentResponse } from './schemas/payment';
import {
  agentListSchema,
  agentResponseSchema,
  deliveryListSchema,
  deliveryResponseSchema,
  shipmentListSchema,
  shipmentResponseSchema,
} from './schemas/delivery';
import type {
  AgentResponse,
  AssignDeliveryRequest,
  CreateAgentRequest,
  DeliveryResponse,
  ShipmentResponse,
} from './schemas/delivery';

/**
 * What every read takes.
 *
 * `signal` exists because TanStack Query passes one into every query function and aborts it on
 * unmount and on key change. Forwarding it is what makes a user who clicks through three
 * products in two seconds issue three requests and keep one, rather than racing three responses
 * into the same cache entry.
 */
export interface CallOptions {
  readonly signal?: AbortSignal | undefined;
}

/* ====================================================================================== */
/* user-service                                                                            */
/* ====================================================================================== */

/**
 * The only unauthenticated write in the app.
 *
 * Verified live: `{ accessToken, tokenType: "Bearer", expiresInSeconds: 3600, userId, roles }`.
 * A bad credential returns 401 UNAUTHORIZED with a problem+json body whose content type carries
 * a charset suffix -- which is the reason for the `startsWith` check in problem.ts.
 */
export async function login(body: LoginRequest): Promise<LoginResponse> {
  return request({ method: 'POST', pathTemplate: '/api/v1/auth/login', body }, loginResponseSchema);
}

/**
 * Registration. ALWAYS grants `["CUSTOMER"]`. Every other role is granted afterwards by an admin,
 * through `grantRole` below.
 *
 * Returns 201. A duplicate email returns 409 `EMAIL_ALREADY_EXISTS` -- its own code, so the form
 * can put the error on the email field and offer "sign in instead" rather than showing a generic
 * conflict.
 */
export async function registerUser(body: RegisterUserRequest): Promise<UserResponse> {
  return request({ method: 'POST', pathTemplate: '/api/v1/users', body }, userResponseSchema);
}

export async function getUser(userId: string, options?: CallOptions): Promise<UserResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/users/:userId',
      params: { userId },
      signal: options?.signal,
    },
    userResponseSchema,
  );
}

/**
 * `UpdateUserRequest` carries firstName, lastName and phoneNumber. That is the entire editable
 * surface of a user's profile -- no email or password change. Roles have their own endpoints.
 */
export async function updateUser(userId: string, body: UpdateUserRequest): Promise<UserResponse> {
  return request(
    { method: 'PUT', pathTemplate: '/api/v1/users/:userId', params: { userId }, body },
    userResponseSchema,
  );
}

/**
 * ADMIN only. The one way to find a user without already holding their id: there is still no
 * user list. An unknown email is a 404.
 */
export async function lookupUserByEmail(
  email: string,
  options?: CallOptions,
): Promise<UserResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/users/lookup',
      query: { email },
      signal: options?.signal,
    },
    userResponseSchema,
  );
}

/**
 * ADMIN only, and idempotent both ways: granting a held role, or revoking one the user does not
 * have, answers 200 with the unchanged user. Roles travel in the JWT, so the change reaches the
 * user at their next sign-in -- and a revoked role keeps working until their current token
 * expires. Revoking your own ADMIN is a 409 `CANNOT_REVOKE_OWN_ADMIN`.
 */
export async function grantRole(userId: string, role: HumanRole): Promise<UserResponse> {
  return request(
    { method: 'PUT', pathTemplate: '/api/v1/users/:userId/roles/:role', params: { userId, role } },
    userResponseSchema,
  );
}

export async function revokeRole(userId: string, role: HumanRole): Promise<UserResponse> {
  return request(
    {
      method: 'DELETE',
      pathTemplate: '/api/v1/users/:userId/roles/:role',
      params: { userId, role },
    },
    userResponseSchema,
  );
}

export async function listAddresses(
  userId: string,
  options?: CallOptions,
): Promise<AddressResponse[]> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/users/:userId/addresses',
      params: { userId },
      signal: options?.signal,
    },
    addressListSchema,
  );
}

export async function createAddress(
  userId: string,
  body: AddressRequest,
): Promise<AddressResponse> {
  return request(
    {
      method: 'POST',
      pathTemplate: '/api/v1/users/:userId/addresses',
      params: { userId },
      body,
    },
    addressResponseSchema,
  );
}

export async function updateAddress(
  userId: string,
  addressId: string,
  body: AddressRequest,
): Promise<AddressResponse> {
  return request(
    {
      method: 'PUT',
      pathTemplate: '/api/v1/users/:userId/addresses/:addressId',
      params: { userId, addressId },
      body,
    },
    addressResponseSchema,
  );
}

/** Returns 204, although the document says 200. `requestNoContent` expects nothing back. */
export async function deleteAddress(userId: string, addressId: string): Promise<void> {
  return requestNoContent({
    method: 'DELETE',
    pathTemplate: '/api/v1/users/:userId/addresses/:addressId',
    params: { userId, addressId },
  });
}

/* ====================================================================================== */
/* product-service                                                                         */
/* ====================================================================================== */

/**
 * The catalog. `permitAll()`, so this works with no token -- which is what makes the anonymous
 * surface after a page refresh genuinely useful rather than merely non-broken.
 *
 * The query members are listed one by one rather than spread, so this file states exactly what
 * goes on the wire: `page`, `size` and `sort` are the three that no OpenAPI document mentions
 * and that the endpoint nonetheless honours.
 */
export async function listProducts(
  query: ProductListQuery = {},
  options?: CallOptions,
): Promise<ProductPageResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/products',
      query: {
        categoryId: query.categoryId,
        minPrice: query.minPrice,
        maxPrice: query.maxPrice,
        search: query.search,
        page: query.page,
        size: query.size,
        sort: query.sort,
      },
      signal: options?.signal,
    },
    productPageResponseSchema,
  );
}

export async function getProduct(
  productId: string,
  options?: CallOptions,
): Promise<ProductResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/products/:productId',
      params: { productId },
      signal: options?.signal,
    },
    productResponseSchema,
  );
}

/**
 * ADMIN only. Returns 201 with `createdAt: null` and `updatedAt: null` -- see the note on
 * `productResponseSchema`, which is why both are `.nullable()` and why a form that shows
 * "created at" from this response must handle null.
 *
 * `active` defaults to FALSE on the server when omitted. Send it explicitly.
 */
export async function createProduct(body: ProductRequest): Promise<ProductResponse> {
  return request({ method: 'POST', pathTemplate: '/api/v1/products', body }, productResponseSchema);
}

/**
 * ADMIN only, and A FULL REPLACE. Every field the form omits is overwritten with its default --
 * verified live: a PUT without `description` returned `"description":null` on a product that had
 * one. Load the current values in, send them all back.
 */
export async function updateProduct(
  productId: string,
  body: ProductRequest,
): Promise<ProductResponse> {
  return request(
    {
      method: 'PUT',
      pathTemplate: '/api/v1/products/:productId',
      params: { productId },
      body,
    },
    productResponseSchema,
  );
}

export async function deleteProduct(productId: string): Promise<void> {
  return requestNoContent({
    method: 'DELETE',
    pathTemplate: '/api/v1/products/:productId',
    params: { productId },
  });
}

export async function listCategories(options?: CallOptions): Promise<CategoryResponse[]> {
  return request(
    { method: 'GET', pathTemplate: '/api/v1/categories', signal: options?.signal },
    categoryListSchema,
  );
}

export async function getCategory(
  categoryId: string,
  options?: CallOptions,
): Promise<CategoryResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/categories/:categoryId',
      params: { categoryId },
      signal: options?.signal,
    },
    categoryResponseSchema,
  );
}

export async function createCategory(body: CategoryRequest): Promise<CategoryResponse> {
  return request(
    { method: 'POST', pathTemplate: '/api/v1/categories', body },
    categoryResponseSchema,
  );
}

/** A full replace, like `updateProduct`. A PUT without `description` nulls a stored one. */
export async function updateCategory(
  categoryId: string,
  body: CategoryRequest,
): Promise<CategoryResponse> {
  return request(
    {
      method: 'PUT',
      pathTemplate: '/api/v1/categories/:categoryId',
      params: { categoryId },
      body,
    },
    categoryResponseSchema,
  );
}

export async function deleteCategory(categoryId: string): Promise<void> {
  return requestNoContent({
    method: 'DELETE',
    pathTemplate: '/api/v1/categories/:categoryId',
    params: { categoryId },
  });
}

/* ====================================================================================== */
/* inventory-service                                                                       */
/* ====================================================================================== */

/**
 * PUBLIC. `permitAll()` at inventory-service SecurityConfig:64, verified live with no
 * Authorization header at all.
 *
 * This single fact is what lets the UI PREVENT a doomed order instead of explaining a FAILED one
 * it legally cannot explain: the product page and the cart show live availability to anonymous
 * browsers too, and Place Order is blocked per line when `totalAvailable < quantity`. Advisory,
 * not authoritative -- stock can reach zero between this read and the POST, and the FAILED screen
 * is the honest fallback.
 */
export async function getInventorySummary(
  productId: string,
  options?: CallOptions,
): Promise<InventorySummaryResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/inventory/:productId',
      params: { productId },
      signal: options?.signal,
    },
    inventorySummaryResponseSchema,
  );
}

/**
 * ADMIN **or** WAREHOUSE_MANAGER (`MANAGED_ROLES`, inventory-service SecurityConfig:41,67).
 *
 * CREATE-ONLY. There is no adjust, no restock and no PUT anywhere in the six documents, so a
 * quantity cannot be changed through this API. A second call for the same
 * `(productId, warehouseId)` pair returns 409 CONFLICT -- verified live.
 */
export async function createInventory(body: InventoryCreateRequest): Promise<InventoryResponse> {
  return request(
    { method: 'POST', pathTemplate: '/api/v1/inventory', body },
    inventoryResponseSchema,
  );
}

/** ADMIN or WAREHOUSE_MANAGER. */
export async function listWarehouses(options?: CallOptions): Promise<WarehouseResponse[]> {
  return request(
    { method: 'GET', pathTemplate: '/api/v1/warehouses', signal: options?.signal },
    warehouseListSchema,
  );
}

/** ADMIN or WAREHOUSE_MANAGER. */
export async function getWarehouse(
  warehouseId: string,
  options?: CallOptions,
): Promise<WarehouseResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/warehouses/:warehouseId',
      params: { warehouseId },
      signal: options?.signal,
    },
    warehouseResponseSchema,
  );
}

/** ADMIN or WAREHOUSE_MANAGER. Returns 201. */
export async function createWarehouse(body: WarehouseRequest): Promise<WarehouseResponse> {
  return request(
    { method: 'POST', pathTemplate: '/api/v1/warehouses', body },
    warehouseResponseSchema,
  );
}

/* ====================================================================================== */
/* order-service                                                                           */
/* ====================================================================================== */

/**
 * PLACE AN ORDER. The one call in this application where getting the details wrong costs a
 * customer money, so read all four notes.
 *
 * 1. `idempotencyKey` IS REQUIRED BY THIS FUNCTION, although the header is optional on the
 *    server. There is no legitimate caller that should place an order without one, and making it
 *    an optional parameter is how one ends up shipped.
 *
 * 2. A 201 DOES NOT MEAN THE ORDER SUCCEEDED. It means the order EXISTS, at status CREATED. The
 *    saga then runs over seconds and can end FAILED -- an order placed while verifying this plan
 *    returned 201 and reached FAILED in about four seconds because `totalAvailable` was 0. The
 *    success screen says "Order placed. We're working on it.", never "confirmed".
 *
 * 3. A REPLAY IS INDISTINGUISHABLE FROM A CREATE, AND THE CLIENT MUST NOT TRY. Verified live:
 *    the same key with the same body returns 201 AGAIN, with the SAME order id. So the status
 *    code carries no information about which happened. The only correct behaviour is to navigate
 *    to the returned id, which is right either way.
 *
 * 4. A 409 HERE MEANS THE KEY WAS USED FOR A DIFFERENT BODY -- and it is disambiguated BY CALL
 *    SITE, never by code. The build serving localhost:8080 answers `error: "CONFLICT"` (verified
 *    live, same key with `quantity` changed from 2 to 3); HEAD of this repository answers
 *    `IDEMPOTENCY_KEY_CONFLICT` after commit 4aecb3b. `isIdempotencyKeyConflict` in problem.ts
 *    tells you when the server was able to be specific, and is not the test.
 *    The response is to reopen the cart, say "nothing was ordered twice", show the support code,
 *    and `console.error` the key lifecycle state. NEVER to re-mint a key and re-POST -- that is
 *    the one path to a genuine duplicate order.
 *
 * NOT AUTO-RETRIED ON A 4xx, ever. On a network failure or a 502/503/504 a retry is safe and
 * correct precisely BECAUSE the same key is reused: `UNIQUE (user_id, idempotency_key)` means a
 * concurrent loser re-reads the winner's row.
 */
export async function createOrder(
  body: CreateOrderRequest,
  idempotencyKey: string,
): Promise<OrderResponse> {
  return request(
    { method: 'POST', pathTemplate: '/api/v1/orders', body, idempotencyKey },
    orderResponseSchema,
  );
}

export async function getOrder(orderId: string, options?: CallOptions): Promise<OrderResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/orders/:orderId',
      params: { orderId },
      signal: options?.signal,
    },
    orderResponseSchema,
  );
}

/**
 * THE POLLED CALL, and the only thing this app polls on an interval.
 *
 * Two fields, so it is cheap enough to ask for every second during the saga's first ten seconds.
 * `refetchInterval` is `nextPollDelay(q.state.data?.status, elapsedMs, attempts)` from
 * src/domain/polling.ts, which returns null -- STOPPING the interval -- at SHIPMENT_CREATED and
 * OUT_FOR_DELIVERY, because those two advance on human action measured in hours or days, not on
 * the saga. Polling them every second is a load generator, not a feature.
 */
export async function getOrderStatus(
  orderId: string,
  options?: CallOptions,
): Promise<OrderStatusResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/orders/:orderId/status',
      params: { orderId },
      signal: options?.signal,
    },
    orderStatusResponseSchema,
  );
}

/**
 * One user's orders, paged.
 *
 * Authorised with `#userId.toString() == authentication.name or hasRole('ADMIN')`, so an ADMIN
 * reading this path for another user succeeds -- which is exactly why
 * `queryKeys.orders.byUserPage` includes `userId`. Flat `page`/`size`/`sort` and NOT the
 * document's `pageable` object parameter.
 */
export async function listUserOrders(
  userId: string,
  query: OrderListQuery = {},
  options?: CallOptions,
): Promise<OrderPageResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/orders/user/:userId',
      params: { userId },
      query: { page: query.page, size: query.size, sort: query.sort },
      signal: options?.signal,
    },
    orderPageResponseSchema,
  );
}

/**
 * CANCEL. Legal only up to PAID.
 *
 * `OrderStatus.isCancellable()` is `canTransitionTo(CANCELLED)`, and the transition table gives
 * exactly CREATED, INVENTORY_RESERVATION_PENDING, INVENTORY_RESERVED, PAYMENT_PENDING and PAID.
 * Later it returns 409 -- verified live on a SHIPMENT_CREATED order, with the detail "Cannot move
 * an order from SHIPMENT_CREATED to CANCELLED".
 *
 * THERE IS NO IDEMPOTENCY KEY ON THIS ENDPOINT, so it is never auto-retried. And losing the race
 * is a FIRST-CLASS OUTCOME rather than an error: the status the UI holds is up to one poll
 * interval stale, and the platform's own `scripts/e2e-smoke.sh` has to retry this call. On a 409
 * the answer is to invalidate, refetch, and render a message derived from the FRESH status --
 * not an error toast, and never the server's `detail` ("Cannot move an order from FAILED to
 * CANCELLED" is true and useless to a shopper).
 *
 * A successful cancel returns 200 with the order at CANCELLED. Compensation (stock release, any
 * refund) is ASYNCHRONOUS -- ADR 008 -- so the copy is "Cancelling: releasing stock and issuing
 * any refund", not "cancelled and refunded".
 */
export async function cancelOrder(orderId: string): Promise<OrderResponse> {
  return request(
    {
      method: 'POST',
      pathTemplate: '/api/v1/orders/:orderId/cancel',
      params: { orderId },
    },
    orderResponseSchema,
  );
}

/* ====================================================================================== */
/* payment-service -- ADMIN only                                                           */
/* ====================================================================================== */

/**
 * ADMIN only (`hasAnyRole("ADMIN","SERVICE")`, payment-service SecurityConfig:58). Verified: a
 * CUSTOMER token gets 403.
 *
 * This is the reason the customer FAILED screen cannot say whether they were charged, and the
 * reason payment lookup lives in the admin dispatch console and nowhere else.
 */
export async function getPaymentByOrder(
  orderId: string,
  options?: CallOptions,
): Promise<PaymentResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/payments/order/:orderId',
      params: { orderId },
      signal: options?.signal,
    },
    paymentResponseSchema,
  );
}

/** ADMIN only. */
export async function getPayment(
  paymentId: string,
  options?: CallOptions,
): Promise<PaymentResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/payments/:paymentId',
      params: { paymentId },
      signal: options?.signal,
    },
    paymentResponseSchema,
  );
}

/* ====================================================================================== */
/* delivery-service                                                                        */
/* ====================================================================================== */

/**
 * ADMIN only, and the ONLY list-ish view of orders in the entire API.
 *
 * BY CONSTRUCTION it contains only orders that reached SHIPMENT_CREATED, which excludes exactly
 * the FAILED and stuck population an admin needs. That is why there is no admin order console:
 * one built on this would look like order management while hiding the stuck sagas. The endpoint
 * that would fix it is an ADMIN-only `GET /api/v1/orders?status=&page=`.
 */
export async function listShipments(options?: CallOptions): Promise<ShipmentResponse[]> {
  return request(
    { method: 'GET', pathTemplate: '/api/v1/shipments', signal: options?.signal },
    shipmentListSchema,
  );
}

/** ADMIN only. A customer cannot read their own shipment -- verified 403. */
export async function getShipment(
  shipmentId: string,
  options?: CallOptions,
): Promise<ShipmentResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/shipments/:shipmentId',
      params: { shipmentId },
      signal: options?.signal,
    },
    shipmentResponseSchema,
  );
}

/** ADMIN only. The admin order-lookup screen's bridge from an order id to its shipment. */
export async function getShipmentByOrder(
  orderId: string,
  options?: CallOptions,
): Promise<ShipmentResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/shipments/order/:orderId',
      params: { orderId },
      signal: options?.signal,
    },
    shipmentResponseSchema,
  );
}

/**
 * ADMIN only. Returns 201 and a `DeliveryResponse` -- not a shipment, although the path is under
 * `/shipments`, and not the 200 the document promises.
 *
 * This is the human action that moves an order SHIPMENT_CREATED -> OUT_FOR_DELIVERY, via the
 * `DeliveryAssigned` event (`OrderSagaEventHandler:97`). It is why interval polling stops at
 * SHIPMENT_CREATED: the next step waits on a person.
 */
export async function assignDelivery(
  shipmentId: string,
  body: AssignDeliveryRequest,
): Promise<DeliveryResponse> {
  return request(
    {
      method: 'POST',
      pathTemplate: '/api/v1/shipments/:shipmentId/assign',
      params: { shipmentId },
      body,
    },
    deliveryResponseSchema,
  );
}

/** ADMIN only. */
export async function listAgents(options?: CallOptions): Promise<AgentResponse[]> {
  return request(
    { method: 'GET', pathTemplate: '/api/v1/agents', signal: options?.signal },
    agentListSchema,
  );
}

/** ADMIN only. */
export async function getAgent(agentId: string, options?: CallOptions): Promise<AgentResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/agents/:agentId',
      params: { agentId },
      signal: options?.signal,
    },
    agentResponseSchema,
  );
}

/**
 * ADMIN only. Returns 201.
 *
 * `userId` is a raw UUID typed into a text field, because there is NO user list and NO user
 * search anywhere in this API -- only `GET /api/v1/users/{id}` -- so there is nothing to build a
 * picker from. Verified live: it does NOT require the named user to already hold
 * DELIVERY_AGENT, and a second call for the same `userId` returns 409 CONFLICT.
 */
export async function createAgent(body: CreateAgentRequest): Promise<AgentResponse> {
  return request({ method: 'POST', pathTemplate: '/api/v1/agents', body }, agentResponseSchema);
}

/**
 * An agent's worklist. TAKES THE AGENT'S **USER** ID, not `AgentResponse.id`.
 *
 * Verified live. Passing the agent id returns an EMPTY ARRAY rather than an error, so the wrong
 * argument looks exactly like "no deliveries assigned" -- the quietest possible failure, and the
 * reason the parameter is named `agentUserId` here and in `queryKeys.deliveries.byAgentUser`.
 */
export async function listDeliveriesForAgentUser(
  agentUserId: string,
  options?: CallOptions,
): Promise<DeliveryResponse[]> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/deliveries/agent/:agentUserId',
      params: { agentUserId },
      signal: options?.signal,
    },
    deliveryListSchema,
  );
}

export async function getDelivery(
  deliveryId: string,
  options?: CallOptions,
): Promise<DeliveryResponse> {
  return request(
    {
      method: 'GET',
      pathTemplate: '/api/v1/deliveries/:deliveryId',
      params: { deliveryId },
      signal: options?.signal,
    },
    deliveryResponseSchema,
  );
}

/**
 * The agent's one action. Returns 200 with `status: "COMPLETED"` and `deliveredAt` populated --
 * verified live.
 *
 * This is the human action that moves an order OUT_FOR_DELIVERY -> DELIVERED, via the
 * `DeliveryCompleted` event (`OrderSagaEventHandler:102`). There is no idempotency key on it, so
 * it is never auto-retried; a second call on an already-completed delivery is the case the
 * button must handle rather than assume away.
 */
export async function completeDelivery(deliveryId: string): Promise<DeliveryResponse> {
  return request(
    {
      method: 'POST',
      pathTemplate: '/api/v1/deliveries/:deliveryId/complete',
      params: { deliveryId },
    },
    deliveryResponseSchema,
  );
}
