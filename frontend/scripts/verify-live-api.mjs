#!/usr/bin/env node
/**
 * Does the running platform still BEHAVE the way this client depends on?
 *
 * `check:contract` diffs the OpenAPI documents; this asserts what the documents do not say and
 * the client relies on anyway. Each check names the code that depends on it.
 *
 *   node scripts/verify-live-api.mjs                        # against http://localhost:8080
 *   node scripts/verify-live-api.mjs --base http://host:8080
 *
 * IT WRITES: one registered customer, one address and one order (against the first active
 * product with stock). Point it at a development stack, never at production.
 *
 * Plain Node 22 `fetch`, no dependencies, absolute URLs built from --base (src/lib/api/http.ts
 * builds relative ones, which Node cannot resolve -- see the note on `buildUrl`).
 */

const baseIndex = process.argv.indexOf('--base');
const BASE = (baseIndex > 0 ? process.argv[baseIndex + 1] : 'http://localhost:8080').replace(/\/+$/, '');

let failures = 0;
function check(name, ok, detail = '') {
  console.log(`  ${ok ? 'ok  ' : 'FAIL'}  ${name}${ok || !detail ? '' : `  -- ${detail}`}`);
  if (!ok) failures += 1;
}

async function call(method, path, { token, body, headers = {} } = {}) {
  const res = await fetch(BASE + path, {
    method,
    headers: {
      Accept: 'application/json, application/problem+json',
      ...(body ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...headers,
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  let json = null;
  try {
    json = text ? JSON.parse(text) : null;
  } catch {
    // Not JSON; `text` is kept for the failure message.
  }
  return { res, json, text };
}

console.log(`verify:live  base=${BASE}\n`);

// 1. Paging that no document declares (queryFromParams in CatalogPage, listProducts).
{
  const { res, json } = await call('GET', '/api/v1/products?page=0&size=2&sort=price,desc');
  check('products honour undocumented page/size/sort', res.status === 200 && json?.size === 2 && Array.isArray(json?.content));
  const prices = (json?.content ?? []).map((p) => p.price);
  check('sort=price,desc actually sorts', prices.length < 2 || prices[0] >= prices[1], JSON.stringify(prices));
}

// 2. A 401 is problem+json -- matched with startsWith, whatever the charset (problem.ts DETAIL 1).
{
  const sent = crypto.randomUUID();
  const { res, json } = await call('GET', '/api/v1/orders/user/00000000-0000-0000-0000-000000000000', {
    headers: { 'X-Correlation-Id': sent },
  });
  const type = res.headers.get('content-type') ?? '';
  check('401 without a token', res.status === 401, String(res.status));
  check('401 content type starts with application/problem+json', type.toLowerCase().startsWith('application/problem+json'), type);
  check('401 carries the stable code UNAUTHORIZED', json?.error === 'UNAUTHORIZED', JSON.stringify(json));
  // 3. The gateway forwards the client's correlation id instead of minting one (http.ts).
  const header = (res.headers.get('x-correlation-id') ?? '').split(',')[0].trim();
  check('the correlation id we sent is the one that comes back', header === sent || json?.correlationId === sent, header);
}

// 4. Live stock is public (StockLine, useCartPricing).
const page = await call('GET', '/api/v1/products?size=50');
const products = (page.json?.content ?? []).filter((p) => p.active);
let sellable = null;
for (const p of products) {
  const stock = await call('GET', `/api/v1/inventory/${p.id}`);
  if (stock.res.status === 200 && stock.json.totalAvailable >= 2) {
    sellable = p;
    check('inventory summary readable with no token', true);
    break;
  }
}
if (!sellable) {
  check('found an active product with stock to order', false, 'seed one (docs/local-development.md) and rerun');
} else {
  // 5. Idempotent create: same key + same body -> 201 with the SAME id; different body -> 409.
  const email = `verify-live-${Date.now()}@example.test`;
  const password = 'verify-live-password';
  await call('POST', '/api/v1/users', { body: { email, password, firstName: 'Verify', lastName: 'Live' } });
  const login = await call('POST', '/api/v1/auth/login', { body: { email, password } });
  const token = login.json?.accessToken;
  const userId = login.json?.userId;
  check('register + login', Boolean(token && userId), login.text.slice(0, 120));
  const address = await call('POST', `/api/v1/users/${userId}/addresses`, {
    token,
    body: { label: 'Verify', street: '1 Verify St', city: 'Testville', postalCode: '00000', country: 'US' },
  });
  const key = crypto.randomUUID();
  const order = { shippingAddressId: address.json?.id, items: [{ productId: sellable.id, quantity: 1 }] };
  const first = await call('POST', '/api/v1/orders', { token, body: order, headers: { 'Idempotency-Key': key } });
  const replay = await call('POST', '/api/v1/orders', { token, body: order, headers: { 'Idempotency-Key': key } });
  check('create answers 201', first.res.status === 201, `${first.res.status} ${first.text.slice(0, 160)}`);
  check('a replay answers 201 with the SAME order id', replay.res.status === 201 && replay.json?.id === first.json?.id);
  const changed = await call('POST', '/api/v1/orders', {
    token,
    body: { ...order, items: [{ productId: sellable.id, quantity: 2 }] },
    headers: { 'Idempotency-Key': key },
  });
  check('the same key with a different body answers 409', changed.res.status === 409, String(changed.res.status));
  check(
    '...with code IDEMPOTENCY_KEY_CONFLICT or CONFLICT',
    ['IDEMPOTENCY_KEY_CONFLICT', 'CONFLICT'].includes(changed.json?.error),
    changed.json?.error,
  );
  // 6. Concurrent duplicates: one order, every caller gets it (usePlaceOrder, OrderService).
  const racingKey = crypto.randomUUID();
  const racing = await Promise.all(
    [1, 2, 3].map(() => call('POST', '/api/v1/orders', { token, body: order, headers: { 'Idempotency-Key': racingKey } })),
  );
  const ids = new Set(racing.map((r) => r.json?.id));
  check('three concurrent same-key creates all answer 201', racing.every((r) => r.res.status === 201), racing.map((r) => r.res.status).join(','));
  check('...with one order id between them', ids.size === 1, [...ids].join(','));
  // 7. Customers cannot read payments (the FAILED screen's honest copy depends on it).
  const payment = await call('GET', `/api/v1/payments/order/${first.json?.id}`, { token });
  check('a customer token gets 403 on payments', payment.res.status === 403, String(payment.res.status));
}

console.log(`\n${failures === 0 ? 'All checks passed.' : `${failures} check(s) FAILED.`}`);
process.exit(failures === 0 ? 0 : 1);
