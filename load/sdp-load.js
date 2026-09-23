// =============================================================================
// Load, stress, spike and soak tests for the Smart Delivery Platform (Phase 23).
// docs/load-testing.md explains how to run it and how to read the result.
//
//   k6 run load/sdp-load.js                                   # smoke profile
//   k6 run -e PROFILE=stress load/sdp-load.js
//   k6 run -e PROFILE=stress -e RATE=0.25 load/sdp-load.js    # a laptop-sized stress
//   k6 run -e PROFILE=load -e HOLD=3m load/sdp-load.js          # shorter steady state
//   k6 run -e PROFILE=soak -e SOAK_DURATION=2h load/sdp-load.js
//
// Everything goes through the gateway, exactly as a browser would: the numbers include
// the gateway hop, JWT verification, and every service-to-service call behind it.
//
// Two scenarios run together:
//   traffic     the pressure: an arrival-rate mix of catalog reads and order placement,
//               shaped by PROFILE. Arrival rate, not a fixed number of users -- a slow
//               system must not get a lighter load because its users are waiting.
//   saga_probe  a few users that place an order and follow it until the saga finishes.
//               A 201 from POST /orders only means "accepted"; the platform's real work
//               (reserve stock, charge, create the shipment) happens afterwards over
//               Kafka. This scenario measures that, under the pressure of the other one.
// =============================================================================
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const GATEWAY = __ENV.GATEWAY || 'http://localhost:8080';
const PROFILE = __ENV.PROFILE || 'smoke';
// Multiplies every arrival rate in the profile: 0.25 turns the production-sized stress
// profile into one a laptop can drive without measuring the laptop.
const RATE = Number(__ENV.RATE || 1);
// Fraction of traffic iterations that place an order (the rest browse the catalog).
const ORDER_RATIO = Number(__ENV.ORDER_RATIO || 0.2);
// Orders are spread over this many products. 1 = every order locks the same inventory
// row, the worst case for contention; a real catalog spreads it.
const PRODUCTS = Number(__ENV.PRODUCTS || 5);
const USERS = Number(__ENV.USERS || 40);
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'admin@smart-delivery.local';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'local-dev-only-admin-password';

const r = (n) => Math.max(1, Math.round(n * RATE));

// Stages are arrival rates (iterations per second) of the traffic scenario.
const PROFILES = {
  smoke: [{ target: r(2), duration: '30s' }],
  load: [
    { target: r(20), duration: '1m' },
    { target: r(20), duration: __ENV.HOLD || '8m' },
    { target: 0, duration: '30s' },
  ],
  stress: [
    { target: r(20), duration: '2m' },   // normal
    { target: r(50), duration: '3m' },   // busy
    { target: r(100), duration: '3m' },  // stress
    { target: r(200), duration: '3m' },  // looking for the breaking point
    { target: r(5), duration: '3m' },    // recovery: does it come back on its own?
  ],
  spike: [
    { target: r(5), duration: '1m' },
    { target: r(150), duration: '10s' }, // faster than any autoscaler reacts
    { target: r(150), duration: '1m' },
    { target: r(5), duration: '10s' },
    { target: r(5), duration: '2m' },    // recovery
  ],
  soak: [
    { target: r(20), duration: '2m' },
    { target: r(20), duration: __ENV.SOAK_DURATION || '2h' },
    { target: 0, duration: '1m' },
  ],
};
if (!PROFILES[PROFILE]) {
  throw new Error(`PROFILE must be one of ${Object.keys(PROFILES).join(', ')}, got ${PROFILE}`);
}
const stages = PROFILES[PROFILE];
const peak = Math.max(...stages.map((s) => s.target));
const totalSeconds = stages.reduce((sum, s) => sum + seconds(s.duration), 0);

// End-to-end saga outcome, measured by the probe.
const sagaTimeToPaid = new Trend('saga_time_to_paid', true);
const sagaCompleted = new Rate('saga_completed');
const sagaFailed = new Counter('saga_failed');
// Shed load, counted separately from other failures: a 503 is the platform protecting
// itself (pool timeout, open circuit breaker), which is a different finding from a 500.
const shed = new Counter('responses_503');

export const options = {
  scenarios: {
    traffic: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: Math.min(50, peak * 2),
      maxVUs: Math.max(50, peak * 4),
      stages,
      exec: 'traffic',
    },
    saga_probe: {
      executor: 'constant-vus',
      vus: 2,
      duration: `${totalSeconds}s`,
      exec: 'sagaProbe',
    },
  },
  thresholds: {
    // Errors seen by callers; 503s count, because a shed request is still a failed one.
    http_req_failed: ['rate<0.01'],
    'http_req_duration{name:catalog}': ['p(95)<300'],
    'http_req_duration{name:create_order}': ['p(95)<800'],
    'http_req_duration{name:order_status}': ['p(95)<300'],
    // The saga, end to end: accepted orders must actually complete, and promptly.
    saga_completed: ['rate>0.99'],
    saga_time_to_paid: ['p(95)<10000'],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function seconds(d) {
  const m = /^(\d+)(s|m|h)$/.exec(d);
  if (!m) throw new Error(`unsupported duration ${d}`);
  return Number(m[1]) * { s: 1, m: 60, h: 3600 }[m[2]];
}

function params(token, extraHeaders, name) {
  const headers = { 'Content-Type': 'application/json', ...(extraHeaders || {}) };
  if (token) headers.Authorization = `Bearer ${token}`;
  return name ? { headers, tags: { name } } : { headers };
}

function mustOk(res, what, expected) {
  if (res.status !== expected) {
    throw new Error(`setup: ${what} returned ${res.status}: ${res.body && res.body.substring(0, 300)}`);
  }
  return res;
}

// -----------------------------------------------------------------------------
// setup: runs once. Products with stock that cannot run out (so FAILED orders mean
// the platform failed, not that the test sold out), and a pool of logged-in customers
// (so the test measures ordering, not BCrypt -- login cost is its own question).
// -----------------------------------------------------------------------------
export function setup() {
  const admin = mustOk(http.post(`${GATEWAY}/api/v1/auth/login`,
    JSON.stringify({ email: ADMIN_EMAIL, password: ADMIN_PASSWORD }), params()), 'admin login', 200)
    .json('accessToken');
  const run = `${Date.now()}`;

  let categoryId = (http.get(`${GATEWAY}/api/v1/categories`).json() || [])[0]?.id;
  if (!categoryId) {
    categoryId = mustOk(http.post(`${GATEWAY}/api/v1/categories`,
      JSON.stringify({ name: `Load ${run}`, description: 'k6' }), params(admin)), 'create category', 201).json('id');
  }
  const warehouseId = mustOk(http.post(`${GATEWAY}/api/v1/warehouses`,
    JSON.stringify({ name: `Load Depot ${run}`, location: '1 Load Road' }), params(admin)), 'create warehouse', 201).json('id');

  const products = [];
  for (let i = 0; i < PRODUCTS; i++) {
    const id = mustOk(http.post(`${GATEWAY}/api/v1/products`, JSON.stringify({
      sku: `LOAD-${run}-${i}`, name: `Load Widget ${i}`, description: 'k6 load test',
      price: '10.00', imageUrl: null, active: true, categoryId,
    }), params(admin)), 'create product', 201).json('id');
    mustOk(http.post(`${GATEWAY}/api/v1/inventory`, JSON.stringify({
      productId: id, warehouseId, availableQuantity: 10000000,
    }), params(admin)), 'stock product', 201);
    products.push(id);
  }

  const users = [];
  for (let i = 0; i < USERS; i++) {
    const email = `load-${run}-${i}@example.com`;
    const password = 'Load-test-pw-123!';
    const id = mustOk(http.post(`${GATEWAY}/api/v1/users`, JSON.stringify({
      email, password, firstName: 'Load', lastName: `User${i}`, phoneNumber: '555-0100',
    }), params()), 'register', 201).json('id');
    const token = mustOk(http.post(`${GATEWAY}/api/v1/auth/login`,
      JSON.stringify({ email, password }), params()), 'login', 200).json('accessToken');
    const addressId = mustOk(http.post(`${GATEWAY}/api/v1/users/${id}/addresses`, JSON.stringify({
      label: 'Home', street: '1 Load Lane', city: 'Testville', state: 'TS',
      postalCode: '00001', country: 'USA', isDefault: true,
    }), params(token)), 'create address', 201).json('id');
    users.push({ token, addressId });
  }
  return { products, users };
}

function placeOrder(data, user) {
  const productId = data.products[Math.floor(Math.random() * data.products.length)];
  const res = http.post(`${GATEWAY}/api/v1/orders`,
    JSON.stringify({ shippingAddressId: user.addressId, items: [{ productId, quantity: 1 }] }),
    params(user.token, { 'Idempotency-Key': crypto.randomUUID() }, 'create_order'));
  if (res.status === 503) shed.add(1, { name: 'create_order' });
  check(res, { 'order accepted (201)': (x) => x.status === 201 });
  return res.status === 201 ? res.json('id') : null;
}

// -----------------------------------------------------------------------------
// traffic: one arrival = one catalog read, or one order plus one status read.
// -----------------------------------------------------------------------------
export function traffic(data) {
  const user = data.users[Math.floor(Math.random() * data.users.length)];

  if (Math.random() >= ORDER_RATIO) {
    const page = Math.floor(Math.random() * 3);
    const res = http.get(`${GATEWAY}/api/v1/products?page=${page}&size=20`, params(null, null, 'catalog'));
    if (res.status === 503) shed.add(1, { name: 'catalog' });
    check(res, { 'catalog 200': (x) => x.status === 200 });
    return;
  }

  const orderId = placeOrder(data, user);
  if (orderId) {
    const res = http.get(`${GATEWAY}/api/v1/orders/${orderId}/status`, params(user.token, null, 'order_status'));
    if (res.status === 503) shed.add(1, { name: 'order_status' });
    check(res, { 'status 200': (x) => x.status === 200 });
  }
}

// -----------------------------------------------------------------------------
// saga_probe: follow one order at a time to the end of the saga.
// -----------------------------------------------------------------------------
const DONE = ['PAID', 'SHIPMENT_CREATED', 'OUT_FOR_DELIVERY', 'DELIVERED'];
const DEAD = ['FAILED', 'CANCELLED'];

export function sagaProbe(data) {
  const user = data.users[__VU % data.users.length];
  const started = Date.now();
  const orderId = placeOrder(data, user);
  if (!orderId) {
    sleep(1);
    return;
  }
  const deadline = started + 60000;
  while (Date.now() < deadline) {
    sleep(0.5);
    const res = http.get(`${GATEWAY}/api/v1/orders/${orderId}/status`, params(user.token, null, 'saga_probe_status'));
    const status = res.status === 200 ? res.json('status') : null;
    if (DONE.includes(status)) {
      sagaTimeToPaid.add(Date.now() - started);
      sagaCompleted.add(true);
      return;
    }
    if (DEAD.includes(status)) {
      sagaFailed.add(1);
      sagaCompleted.add(false);
      return;
    }
  }
  // Still in flight after a minute: as good as failed, from a customer's seat.
  sagaCompleted.add(false);
}
