/**
 * Response bodies CAPTURED FROM THE RUNNING GATEWAY at http://localhost:8080, not invented.
 *
 * Every object below was produced by an actual curl against the live platform while this layer
 * was written, and the field values are the real ones -- including the ones that look like
 * mistakes and are not: `imageUrl: null` on every product, `totalAvailable: 0` on a product with
 * no stock row, `state: null` on an address posted without one, and `createdAt: null` on the 201
 * from `POST /api/v1/products`. Invented fixtures are how a test suite drifts into fiction about
 * a contract it was never shown, and then passes while the app is broken.
 *
 * ONE VALUE IS DELIBERATELY NOT REAL: the access token in `LOGIN_RESPONSE`. A real signed JWT is
 * a working credential for up to an hour against a platform with no revocation
 * (docs/security.md), so committing one would put a live credential in the repository's history
 * forever. The shape is what the test needs; the signature is not.
 *
 * The content types are recorded in `CONTENT_TYPE` below because they are the part most easily
 * lost when a fixture is retyped by hand, and getting them wrong is exactly the bug the
 * `startsWith` check exists to prevent.
 */

/**
 * The three spellings of the problem media type that this platform actually emits, and the reason
 * every comparison in problem.ts is `startsWith`.
 *
 * `PROBLEM_ISO` is what the build serving localhost:8080 returns on a 401 and a 403, because
 * `JwtAuthenticationEntryPoint` and `JwtAccessDeniedHandler` write the body themselves and used
 * to call `getWriter()` without setting an encoding. `PROBLEM_UTF8` is what HEAD returns after
 * commit 4aecb3b fixed that. `PROBLEM_BARE` is what the `@RestControllerAdvice` path returns on
 * everything else, because Spring's message converter sets no charset parameter. An `===`
 * comparison is wrong against all three at once.
 */
export const CONTENT_TYPE = {
  PROBLEM_BARE: 'application/problem+json',
  PROBLEM_ISO: 'application/problem+json;charset=ISO-8859-1',
  PROBLEM_UTF8: 'application/problem+json;charset=UTF-8',
  JSON: 'application/json',
  HTML: 'text/html',
} as const;

const TYPE_URI =
  'https://github.com/yassinefourati/smart-delivery-platform/blob/main/docs/security.md#error-codes';

/* -------------------------------------------------------------------------------------- */
/* Problem bodies                                                                          */
/* -------------------------------------------------------------------------------------- */

/** `GET /api/v1/orders/{id}` with no Authorization header. Arrives as `;charset=ISO-8859-1`. */
export const PROBLEM_401 = {
  type: TYPE_URI,
  title: 'UNAUTHORIZED',
  status: 401,
  detail: 'Authentication is required to access this resource',
  instance: '/api/v1/orders/00000000-0000-0000-0000-000000000000',
  path: '/api/v1/orders/00000000-0000-0000-0000-000000000000',
  timestamp: '2026-09-23T04:03:20.257691448Z',
  error: 'UNAUTHORIZED',
  message: 'Authentication is required to access this resource',
  correlationId: '09c8712c-dcb9-482c-b460-15513dd89c06',
} as const;

/** `GET /api/v1/shipments` with a CUSTOMER token. The 403 that must NOT log anybody out. */
export const PROBLEM_403 = {
  type: TYPE_URI,
  title: 'FORBIDDEN',
  status: 403,
  detail: 'You do not have permission to perform this action',
  instance: '/api/v1/shipments',
  path: '/api/v1/shipments',
  timestamp: '2026-09-23T04:04:00.184473056Z',
  error: 'FORBIDDEN',
  message: 'You do not have permission to perform this action',
  correlationId: 'fbeac481-e512-4196-b902-ef8686b5ed75',
} as const;

export const PROBLEM_404 = {
  type: TYPE_URI,
  title: 'NOT_FOUND',
  status: 404,
  detail: "Order '00000000-0000-0000-0000-000000000000' was not found",
  instance: '/api/v1/orders/00000000-0000-0000-0000-000000000000',
  path: '/api/v1/orders/00000000-0000-0000-0000-000000000000',
  timestamp: '2026-09-23T04:03:27.926289944Z',
  error: 'NOT_FOUND',
  message: "Order '00000000-0000-0000-0000-000000000000' was not found",
  correlationId: '2c212f29-f74d-4e9e-8571-3b3e2fa891ec',
} as const;

/**
 * `POST /api/v1/users` with a bad email, a short password and blank names.
 *
 * Note the `detail`: four failures joined with `"; "`, in no documented order. This is the string
 * it is tempting to parse into a per-field error map, and the format is not contractual -- a copy
 * edit in the server would break the parser silently. VALIDATION_ERROR shows as a form-level
 * banner instead, and this fixture is here so that decision is visible next to the evidence.
 */
export const PROBLEM_400_VALIDATION = {
  type: TYPE_URI,
  title: 'VALIDATION_ERROR',
  status: 400,
  detail:
    'password: must be at least 8 characters; firstName: must not be blank; email: must be a well-formed email address; lastName: must not be blank',
  instance: '/api/v1/users',
  path: '/api/v1/users',
  timestamp: '2026-09-23T04:03:27.997317664Z',
  error: 'VALIDATION_ERROR',
  message:
    'password: must be at least 8 characters; firstName: must not be blank; email: must be a well-formed email address; lastName: must not be blank',
  correlationId: '49f6e1f3-41e3-4465-8071-8e8b93b2cfb7',
} as const;

/**
 * THE IDEMPOTENCY 409, from the build running right now: same `Idempotency-Key`, `quantity`
 * changed from 2 to 3.
 *
 * `error` IS `"CONFLICT"`, NOT `"IDEMPOTENCY_KEY_CONFLICT"`. docs/order-flow.md:136 has promised
 * the specific code since Phase 7 and HEAD now emits it (commit 4aecb3b), but the deployed
 * container predates that commit. A client that branched on the documented code would be broken
 * against this response; one that branched on `CONFLICT` will be broken by the next deploy. Both
 * fixtures exist so the tests prove neither happens.
 *
 * Content type: BARE `application/problem+json`, with no charset parameter -- captured.
 */
export const PROBLEM_409_IDEMPOTENCY_DEPLOYED = {
  type: TYPE_URI,
  title: 'CONFLICT',
  status: 409,
  detail:
    "Idempotency-Key '1aeaf2b1-2b40-46f5-ac15-dc79eb6da212' was already used for a different request",
  instance: '/api/v1/orders',
  path: '/api/v1/orders',
  timestamp: '2026-09-23T04:04:21.491317045Z',
  error: 'CONFLICT',
  message:
    "Idempotency-Key '1aeaf2b1-2b40-46f5-ac15-dc79eb6da212' was already used for a different request",
  correlationId: '711e824e-7447-4cbf-ae62-eb63fd85de84',
} as const;

/** The same situation as HEAD answers it, after commit 4aecb3b gave it a handler of its own. */
export const PROBLEM_409_IDEMPOTENCY_HEAD = {
  ...PROBLEM_409_IDEMPOTENCY_DEPLOYED,
  title: 'IDEMPOTENCY_KEY_CONFLICT',
  error: 'IDEMPOTENCY_KEY_CONFLICT',
} as const;

/**
 * `POST /api/v1/orders/{id}/cancel` on an order that had already reached SHIPMENT_CREATED.
 *
 * The `detail` is true and useless to a shopper, which is why the cancel dialog renders a message
 * derived from the FRESH status instead of this string.
 */
export const PROBLEM_409_ILLEGAL_TRANSITION = {
  type: TYPE_URI,
  title: 'CONFLICT',
  status: 409,
  detail: 'Cannot move an order from SHIPMENT_CREATED to CANCELLED',
  instance: '/api/v1/orders/b487ec64-c65b-4f29-898e-c82d5d790007/cancel',
  path: '/api/v1/orders/b487ec64-c65b-4f29-898e-c82d5d790007/cancel',
  timestamp: '2026-09-23T04:04:48.458702242Z',
  error: 'CONFLICT',
  message: 'Cannot move an order from SHIPMENT_CREATED to CANCELLED',
  correlationId: 'fb8ebf76-713a-4877-8dc1-fed8bcd07058',
} as const;

/**
 * A problem+json body with NEITHER `error` NOR `title`.
 *
 * Not something this platform produces -- it is the case `statusToCode` exists for, and having it
 * as a fixture is what stops somebody deleting that fallback as dead code.
 */
export const PROBLEM_NO_CODE = {
  type: TYPE_URI,
  status: 409,
  detail: 'Something conflicted and the body did not say what.',
} as const;

/**
 * THE GATEWAY'S OWN 404 FOR AN UNROUTED PATH, captured from `GET http://localhost:8080/nope`.
 *
 * `Content-Type: application/json` -- NOT problem+json. And it carries an `error` FIELD whose
 * value is the human string `"Not Found"`. A parser that read `body.error` from any JSON response
 * would hand the UI `code: "Not Found"`, a stable code that is neither stable nor a code. It also
 * arrives with NO `X-Correlation-Id` header at all, which is why the id the client SENT is the
 * final fallback.
 */
export const GATEWAY_404_NOT_PROBLEM = {
  timestamp: '2026-09-23T04:05:05.448+00:00',
  path: '/nope',
  status: 404,
  error: 'Not Found',
  requestId: '43abcdf4-26938',
} as const;

/** What an nginx 502 looks like: HTML, which `JSON.parse` rejects. */
export const NGINX_502_HTML =
  '<html>\r\n<head><title>502 Bad Gateway</title></head>\r\n<body>\r\n<center><h1>502 Bad Gateway</h1></center>\r\n<hr><center>nginx</center>\r\n</body>\r\n</html>\r\n';

/* -------------------------------------------------------------------------------------- */
/* Success bodies                                                                          */
/* -------------------------------------------------------------------------------------- */

/**
 * `POST /api/v1/auth/login` as the bootstrap admin.
 *
 * `expiresInSeconds: 3600` and `tokenType: "Bearer"` are the real values. `accessToken` is a
 * PLACEHOLDER -- see the note at the top of this file. Nothing in this client parses a token, so
 * the placeholder exercises exactly as much of the code as a real one would.
 */
export const LOGIN_RESPONSE = {
  accessToken: 'test.placeholder.not-a-real-signed-token',
  tokenType: 'Bearer',
  expiresInSeconds: 3600,
  userId: '29467c73-8be1-4eda-a993-44ceb940e73e',
  roles: ['ADMIN'],
} as const;

/** A registered customer. Registration always grants exactly `["CUSTOMER"]`. */
export const USER_RESPONSE = {
  id: '46d92ff6-6a3f-40bb-8b65-f6dce4ecb82e',
  email: 's0b-1790136232@example.test',
  firstName: 'Contract',
  lastName: 'Layer',
  phoneNumber: '+15550100',
  active: true,
  roles: ['CUSTOMER'],
  createdAt: '2026-09-23T04:03:52.513731Z',
} as const;

/** Registered WITHOUT a phone number: the key is present and null, never absent. */
export const USER_RESPONSE_NO_PHONE = {
  id: '242a3fbd-8c3c-4abb-bf25-8e31a191ca33',
  email: 'nophone-1790136488@example.test',
  firstName: 'No',
  lastName: 'Phone',
  phoneNumber: null,
  active: true,
  roles: ['CUSTOMER'],
  createdAt: '2026-09-23T04:08:08.446309Z',
} as const;

/** Posted without `state`. Comes back as `state: null`. */
export const ADDRESS_RESPONSE = {
  id: '08c14adb-3ddd-4f1c-92e6-2a979bc2e51f',
  label: 'Work',
  street: '2 Probe Lane',
  city: 'Testville',
  state: null,
  postalCode: '99999',
  country: 'GB',
  isDefault: false,
  createdAt: '2026-09-23T04:08:08.614808Z',
} as const;

/**
 * `GET /api/v1/products?page=0&size=2&sort=price,desc`.
 *
 * `totalPages: 2` is the proof that the undocumented paging parameters work -- `page`, `size` and
 * `sort` are `@Parameter(hidden = true)` on `ProductController:53` and appear in no OpenAPI
 * document, which is the single clearest reason there is no generated client here. And every
 * `imageUrl` is null, on every seeded product, populated by nothing in the repository.
 */
export const PRODUCT_PAGE_RESPONSE = {
  content: [
    {
      id: '1d4902af-68f1-41b6-b30a-68cabb105603',
      sku: 'SMOKE-1790096737-16152',
      name: 'Smoke Widget',
      description: 'Created by the smoke test',
      price: 25.0,
      imageUrl: null,
      active: true,
      categoryId: 'c3795fe2-adb4-4c16-b641-bfbfcad4ad83',
      categoryName: 'Smoke Widgets',
      createdAt: '2026-09-22T17:05:37.887852Z',
      updatedAt: '2026-09-22T17:05:37.887924Z',
    },
    {
      id: '97a6456b-5de9-48f2-82fa-744338555c32',
      sku: 'SMOKE-1790096826-27127',
      name: 'Smoke Widget',
      description: 'Created by the smoke test',
      price: 25.0,
      imageUrl: null,
      active: true,
      categoryId: '45c34d47-1bee-4763-9925-cf3b945ff120',
      categoryName: 'Smoke Widgets 1790096826-27127',
      createdAt: '2026-09-22T17:07:06.982272Z',
      updatedAt: '2026-09-22T17:07:06.982285Z',
    },
  ],
  page: 0,
  size: 2,
  totalElements: 4,
  totalPages: 2,
} as const;

export const PRODUCT_RESPONSE = PRODUCT_PAGE_RESPONSE.content[0];

/**
 * THE 201 FROM `POST /api/v1/products`, AND THE MOST EXPENSIVE FIXTURE IN THIS FILE.
 *
 * `createdAt` and `updatedAt` ARE NULL. The create response is serialised before the JPA audit
 * timestamps are read back, so the POST answers null while an immediate GET of the SAME id answers
 * real instants -- verified both ways. Nothing in the OpenAPI document hints at it and no GET ever
 * shows it. Typing those two fields non-null makes the admin "create product" screen throw
 * CONTRACT_VIOLATION on every SUCCESSFUL create, and the failure looks like a failed create when
 * the row is actually there.
 *
 * `active: false` is also real: `ProductRequest.active` defaults to false when omitted, so a
 * create form that does not send it lands every new product disabled.
 */
export const PRODUCT_CREATED_201 = {
  id: 'a0b919ca-5b42-4e7d-99bf-fd86e197bad1',
  sku: 'CONTRACT-1790136488',
  name: 'Contract Probe',
  description: null,
  price: 9.99,
  imageUrl: null,
  active: false,
  categoryId: 'c3795fe2-adb4-4c16-b641-bfbfcad4ad83',
  categoryName: 'Smoke Widgets',
  createdAt: null,
  updatedAt: null,
} as const;

export const CATEGORY_RESPONSE = {
  id: 'c3795fe2-adb4-4c16-b641-bfbfcad4ad83',
  name: 'Smoke Widgets',
  description: 'Created by the smoke test',
} as const;

/** `PUT /api/v1/categories/{id}` omitting `description` -- a full replace nulls the stored value. */
export const CATEGORY_AFTER_PARTIAL_PUT = {
  id: 'bc799650-ec14-4c92-941c-ebc916833b5a',
  name: 'Contract Cat 1790136316 v2',
  description: null,
} as const;

/**
 * `GET /api/v1/inventory/{productId}` WITH NO TOKEN, for a product with no stock row.
 *
 * 200, `permitAll()` (inventory-service SecurityConfig:64). This response is what lets the cart
 * block a doomed order instead of explaining a FAILED one it legally cannot explain. `warehouses`
 * is `[]` and never a missing key.
 */
export const INVENTORY_SUMMARY_EMPTY = {
  productId: '1d4902af-68f1-41b6-b30a-68cabb105603',
  totalAvailable: 0,
  totalReserved: 0,
  warehouses: [],
} as const;

export const INVENTORY_SUMMARY_IN_STOCK = {
  productId: '1d4902af-68f1-41b6-b30a-68cabb105603',
  totalAvailable: 50,
  totalReserved: 0,
  warehouses: [
    {
      id: 'd1b12d7b-8f8a-4e5c-b91c-49494f2a8885',
      productId: '1d4902af-68f1-41b6-b30a-68cabb105603',
      warehouseId: 'a8a74f04-e747-47eb-be74-0b63b81ba98e',
      warehouseName: 'Contract Depot 1790136250',
      availableQuantity: 50,
      reservedQuantity: 0,
    },
  ],
} as const;

export const WAREHOUSE_RESPONSE = {
  id: 'a8a74f04-e747-47eb-be74-0b63b81ba98e',
  name: 'Contract Depot 1790136250',
  location: '1 Contract Dock',
  active: true,
} as const;

export const INVENTORY_RESPONSE = INVENTORY_SUMMARY_IN_STOCK.warehouses[0];

/**
 * THE 201 FROM `POST /api/v1/orders`, and the identical body returned for a REPLAY of the same key
 * with the same body -- same status, same order id.
 *
 * `status: "CREATED"` is what a 201 means: the order EXISTS. It does not mean it succeeded. The
 * saga then ran and this order reached SHIPMENT_CREATED about five seconds later.
 */
export const ORDER_RESPONSE_CREATED = {
  id: 'b487ec64-c65b-4f29-898e-c82d5d790007',
  userId: '46d92ff6-6a3f-40bb-8b65-f6dce4ecb82e',
  status: 'CREATED',
  shippingAddressId: '17fb9f1f-9dfd-4d8b-9ac0-6987a8b94bcf',
  totalAmount: 50.0,
  items: [
    {
      productId: '1d4902af-68f1-41b6-b30a-68cabb105603',
      productName: 'Smoke Widget',
      unitPrice: 25.0,
      quantity: 2,
      lineTotal: 50.0,
    },
  ],
  createdAt: '2026-09-23T04:04:21.418370Z',
  updatedAt: '2026-09-23T04:04:21.418385Z',
} as const;

/** The same order after the saga finished its own chain. `PAID -> SHIPMENT_CREATED` is the end. */
export const ORDER_RESPONSE_SHIPMENT_CREATED = {
  ...ORDER_RESPONSE_CREATED,
  status: 'SHIPMENT_CREATED',
  updatedAt: '2026-09-23T04:04:26.017490Z',
} as const;

/** `POST /api/v1/orders/{id}/cancel` on a fresh order: 200, and the order is CANCELLED. */
export const ORDER_RESPONSE_CANCELLED = {
  id: '8fc9c485-2e1b-4957-99a6-4f02ade856f2',
  userId: '46d92ff6-6a3f-40bb-8b65-f6dce4ecb82e',
  status: 'CANCELLED',
  shippingAddressId: '17fb9f1f-9dfd-4d8b-9ac0-6987a8b94bcf',
  totalAmount: 25.0,
  items: [
    {
      productId: '1d4902af-68f1-41b6-b30a-68cabb105603',
      productName: 'Smoke Widget',
      unitPrice: 25.0,
      quantity: 1,
      lineTotal: 25.0,
    },
  ],
  createdAt: '2026-09-23T04:08:58.296772Z',
  updatedAt: '2026-09-23T04:08:58.379924Z',
} as const;

export const ORDER_STATUS_RESPONSE = {
  orderId: 'b487ec64-c65b-4f29-898e-c82d5d790007',
  status: 'SHIPMENT_CREATED',
} as const;

export const ORDER_PAGE_RESPONSE = {
  content: [ORDER_RESPONSE_SHIPMENT_CREATED],
  page: 0,
  size: 5,
  totalElements: 1,
  totalPages: 1,
} as const;

/** `GET /api/v1/payments/order/{orderId}` with an ADMIN token. A customer gets 403 here. */
export const PAYMENT_RESPONSE = {
  id: '0975d782-4882-46b4-a7c6-f8eb6cb5788f',
  orderId: 'b487ec64-c65b-4f29-898e-c82d5d790007',
  amount: 50.0,
  currency: 'USD',
  status: 'SUCCESS',
  createdAt: '2026-09-23T04:04:23.356075Z',
  updatedAt: '2026-09-23T04:04:23.356094Z',
} as const;

export const SHIPMENT_RESPONSE = {
  id: '7b689a42-958e-449e-a325-35bf5f4b5a68',
  orderId: 'b487ec64-c65b-4f29-898e-c82d5d790007',
  status: 'CREATED',
  createdAt: '2026-09-23T04:04:24.893544Z',
  updatedAt: '2026-09-23T04:04:24.893625Z',
} as const;

export const AGENT_RESPONSE = {
  id: 'c579625e-98f4-4293-841f-02ff79fdc862',
  userId: '29467c73-8be1-4eda-a993-44ceb940e73e',
  name: 'Probe Agent',
  phone: '+15550199',
} as const;

/**
 * `POST /api/v1/shipments/{id}/assign` -> 201 with a DELIVERY, not a shipment.
 *
 * `deliveredAt: null` for the whole time the delivery is actually in an agent's hands, and
 * `assignedAt` carries NINE fractional digits here against SIX when the same record is read back
 * -- which is why `instantSchema` validates that the string parses rather than asserting a
 * precision.
 */
export const DELIVERY_RESPONSE_ASSIGNED = {
  id: '6f743e58-6133-47dc-b98e-577905d0c0a1',
  shipmentId: '0ad3d8e7-d445-4f8e-b4c7-32a37212f8c8',
  agentId: 'c579625e-98f4-4293-841f-02ff79fdc862',
  status: 'ASSIGNED',
  assignedAt: '2026-09-23T04:08:43.982909487Z',
  deliveredAt: null,
} as const;

export const DELIVERY_RESPONSE_COMPLETED = {
  id: '6f743e58-6133-47dc-b98e-577905d0c0a1',
  shipmentId: '0ad3d8e7-d445-4f8e-b4c7-32a37212f8c8',
  agentId: 'c579625e-98f4-4293-841f-02ff79fdc862',
  status: 'COMPLETED',
  assignedAt: '2026-09-23T04:08:43.982909Z',
  deliveredAt: '2026-09-23T04:08:44.071404970Z',
} as const;
