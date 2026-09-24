/**
 * Plain-language names for the requests on the Help page, so a customer reading "Report a
 * problem" sees "Loaded products" rather than `GET /api/v1/products`. The method and path
 * template still go into the copied report, because that is what support searches the logs for.
 *
 * Keyed by `METHOD pathTemplate`, the exact strings endpoints.ts sends. Anything missing falls
 * back to the raw request, which is still correct, just less friendly.
 */
const DESCRIPTIONS: Readonly<Record<string, string>> = {
  'POST /api/v1/auth/login': 'Signed in',
  'POST /api/v1/users': 'Created an account',
  'GET /api/v1/users/:userId': 'Loaded your profile',
  'PUT /api/v1/users/:userId': 'Saved your profile',
  'GET /api/v1/users/:userId/addresses': 'Loaded your addresses',
  'POST /api/v1/users/:userId/addresses': 'Added an address',
  'PUT /api/v1/users/:userId/addresses/:addressId': 'Updated an address',
  'DELETE /api/v1/users/:userId/addresses/:addressId': 'Deleted an address',
  'GET /api/v1/users/lookup': 'Looked up a user',
  'PUT /api/v1/users/:userId/roles/:role': 'Granted a role',
  'DELETE /api/v1/users/:userId/roles/:role': 'Revoked a role',
  'GET /api/v1/products': 'Loaded products',
  'GET /api/v1/products/:productId': 'Loaded a product',
  'POST /api/v1/products': 'Created a product',
  'PUT /api/v1/products/:productId': 'Updated a product',
  'DELETE /api/v1/products/:productId': 'Deleted a product',
  'GET /api/v1/categories': 'Loaded departments',
  'GET /api/v1/categories/:categoryId': 'Loaded a department',
  'POST /api/v1/categories': 'Created a department',
  'PUT /api/v1/categories/:categoryId': 'Updated a department',
  'DELETE /api/v1/categories/:categoryId': 'Deleted a department',
  'GET /api/v1/inventory/:productId': 'Checked stock',
  'POST /api/v1/inventory': 'Added stock',
  'GET /api/v1/warehouses': 'Loaded warehouses',
  'GET /api/v1/warehouses/:warehouseId': 'Loaded a warehouse',
  'POST /api/v1/warehouses': 'Created a warehouse',
  'PUT /api/v1/warehouses/:warehouseId': 'Updated a warehouse',
  'DELETE /api/v1/warehouses/:warehouseId': 'Deleted a warehouse',
  'POST /api/v1/orders': 'Placed an order',
  'GET /api/v1/orders/:orderId': 'Loaded an order',
  'GET /api/v1/orders/:orderId/status': 'Checked an order’s progress',
  'POST /api/v1/orders/:orderId/cancel': 'Cancelled an order',
  'GET /api/v1/orders/user/:userId': 'Loaded your orders',
  'GET /api/v1/payments/:paymentId': 'Loaded a payment',
  'GET /api/v1/payments/order/:orderId': 'Loaded an order’s payment',
  'GET /api/v1/shipments': 'Loaded shipments',
  'GET /api/v1/shipments/:shipmentId': 'Loaded a shipment',
  'GET /api/v1/shipments/order/:orderId': 'Loaded an order’s shipment',
  'POST /api/v1/shipments/:shipmentId/assign': 'Assigned a courier',
  'GET /api/v1/agents': 'Loaded couriers',
  'GET /api/v1/agents/:agentId': 'Loaded a courier',
  'POST /api/v1/agents': 'Created a courier',
  'GET /api/v1/deliveries/:deliveryId': 'Loaded a delivery',
  'POST /api/v1/deliveries/:deliveryId/complete': 'Marked a delivery as delivered',
  'GET /api/v1/deliveries/agent/:agentUserId': 'Loaded your deliveries',
};

export function describeRequest(method: string, pathTemplate: string): string {
  return DESCRIPTIONS[`${method} ${pathTemplate}`] ?? `${method} ${pathTemplate}`;
}

export type Outcome = 'ok' | 'problem' | 'unreachable';

/**
 * 2xx and 3xx worked. 0 means no response at all (offline, or the server was unreachable).
 * Everything else, 4xx included, is shown as a problem: from where the customer sits, a
 * 403 or a 409 is still "that did not work", and it is exactly what support wants to see.
 */
export function outcomeOf(status: number): Outcome {
  if (status === 0) return 'unreachable';
  return status < 400 ? 'ok' : 'problem';
}
