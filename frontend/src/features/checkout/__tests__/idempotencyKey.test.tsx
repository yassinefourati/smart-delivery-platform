import { fireEvent, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { PROBLEM_401 } from '../../../lib/api/__tests__/fixtures';
import {
  http,
  jsonResponse,
  problemResponse,
  server,
  setupTestServer,
} from '../../../lib/api/__tests__/testServer';
import {
  address,
  catalogReads,
  freshOrder,
  otherAddress,
  seedCart,
  storedCart,
} from '../../../test/apiHandlers';
import { renderApp } from '../../../test/renderApp';

/**
 * THE test of the checkout. Every POST's Idempotency-Key is recorded, and each scenario that
 * could plausibly produce a second order must produce ONE key -- the server's replay rule then
 * guarantees one order. A genuine edit must produce a DIFFERENT key, or the server would 409.
 */
setupTestServer();

let keys: string[] = [];
let responses: (() => Response)[] = [];

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  keys = [];
  responses = [];
  server.use(
    ...catalogReads,
    http.get('/api/v1/users/:userId/addresses', () => jsonResponse([address, otherAddress])),
    http.get('/api/v1/orders/:orderId', () => jsonResponse(freshOrder('CREATED'))),
    http.get('/api/v1/orders/:orderId/status', () =>
      jsonResponse({ orderId: freshOrder('CREATED').id, status: 'CREATED' }),
    ),
    http.post('/api/v1/orders', ({ request: incoming }) => {
      keys.push(incoming.headers.get('Idempotency-Key') ?? 'MISSING');
      const next = responses.shift();
      return next ? next() : jsonResponse(freshOrder('CREATED'), 201);
    }),
  );
});

async function placeOrder() {
  const button = await screen.findByRole('button', { name: 'Place order' });
  await waitFor(() => expect(button).toBeEnabled());
  return button;
}

describe('the Idempotency-Key on POST /api/v1/orders', () => {
  it('is minted on entering checkout, before any click, and persisted', async () => {
    seedCart();
    renderApp('/checkout', { signedInAs: ['CUSTOMER'] });
    await placeOrder();
    expect(storedCart().checkoutKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(keys).toEqual([]);
  });

  it('survives a double-click: one request, one key', async () => {
    seedCart();
    renderApp('/checkout', { signedInAs: ['CUSTOMER'] });
    const button = await placeOrder();
    await userEvent.dblClick(button);
    expect(await screen.findByText(/We're working on it/)).toBeInTheDocument();
    expect(new Set(keys).size).toBe(1);
    expect(keys).toHaveLength(1);
  });

  it('sends ONE request for two clicks in the same frame, before React has re-rendered', async () => {
    // user-event yields between the clicks of a dblclick, so React disables the button in
    // time. A real browser does not wait: the live walk-through sent two concurrent POSTs.
    seedCart();
    renderApp('/checkout', { signedInAs: ['CUSTOMER'] });
    const button = await placeOrder();
    fireEvent.click(button);
    fireEvent.click(button);
    expect(await screen.findByText(/We're working on it/)).toBeInTheDocument();
    expect(keys).toHaveLength(1);
  });

  it('is reused by the automatic retry after a 503', async () => {
    seedCart();
    responses = [
      () =>
        problemResponse(503, {
          error: 'SERVICE_UNAVAILABLE',
          status: 503,
          detail: 'down',
          correlationId: 'c-1',
        }),
    ];
    renderApp('/checkout', { signedInAs: ['CUSTOMER'] });
    await userEvent.click(await placeOrder());
    expect(
      await screen.findByText(/We're working on it/, undefined, { timeout: 8000 }),
    ).toBeInTheDocument();
    expect(keys).toHaveLength(2);
    expect(new Set(keys).size).toBe(1);
  }, 15_000);

  it('is reused by a manual retry after a network failure', async () => {
    seedCart();
    responses = [() => Response.error(), () => Response.error(), () => Response.error()];
    renderApp('/checkout', { signedInAs: ['CUSTOMER'] });
    await userEvent.click(await placeOrder());
    expect(
      await screen.findByText(/Pressing Place order again is safe/, undefined, { timeout: 8000 }),
    ).toBeInTheDocument();
    await userEvent.click(await placeOrder());
    expect(await screen.findByText(/We're working on it/)).toBeInTheDocument();
    expect(keys.length).toBeGreaterThanOrEqual(2);
    expect(new Set(keys).size).toBe(1);
  }, 15_000);

  it('survives a page refresh (remount) mid-checkout', async () => {
    seedCart();
    const first = renderApp('/checkout', { signedInAs: ['CUSTOMER'] });
    await placeOrder();
    const minted = storedCart().checkoutKey;
    first.unmount();
    renderApp('/checkout', { signedInAs: ['CUSTOMER'] });
    await userEvent.click(await placeOrder());
    await screen.findByText(/We're working on it/);
    expect(keys).toEqual([minted]);
  });

  it('is kept when a 401 ends the session mid-checkout, so the re-login retry is safe', async () => {
    seedCart();
    responses = [() => problemResponse(401, PROBLEM_401)];
    const view = renderApp('/checkout', { signedInAs: ['CUSTOMER'] });
    const minted = await placeOrder().then(() => storedCart().checkoutKey);
    await userEvent.click(await placeOrder());
    await waitFor(() => expect(view.router.state.location.pathname).toBe('/login'));
    expect(storedCart().checkoutKey).toBe(minted);
    expect(storedCart().lines).toHaveLength(1);
    view.unmount();
    renderApp('/checkout', { signedInAs: ['CUSTOMER'] });
    await userEvent.click(await placeOrder());
    await screen.findByText(/We're working on it/);
    expect(new Set(keys)).toEqual(new Set([minted]));
  });

  it('ROTATES when the order changes, because the old key now describes a different body', async () => {
    seedCart();
    renderApp('/checkout', { signedInAs: ['CUSTOMER'] });
    await placeOrder();
    const before = storedCart().checkoutKey;
    await userEvent.click(screen.getByRole('radio', { name: /Home/ }));
    await waitFor(() => expect(storedCart().checkoutKey).not.toBe(before));
    await userEvent.click(await placeOrder());
    await screen.findByText(/We're working on it/);
    expect(keys).toHaveLength(1);
    expect(keys[0]).not.toBe(before);
  });

  it('is cleared with the cart after a 201', async () => {
    seedCart();
    renderApp('/checkout', { signedInAs: ['CUSTOMER'] });
    await userEvent.click(await placeOrder());
    await screen.findByText(/We're working on it/);
    expect(storedCart().checkoutKey).toBeNull();
    expect(storedCart().lines).toEqual([]);
  });

  it('on a 409 keeps the key, says nothing was ordered twice, and never silently re-posts', async () => {
    seedCart();
    responses = [
      () =>
        problemResponse(409, {
          error: 'CONFLICT',
          status: 409,
          detail: 'Idempotency key reused',
          correlationId: 'abcdef12-0000',
        }),
    ];
    vi.spyOn(console, 'error').mockImplementation(() => {});
    renderApp('/checkout', { signedInAs: ['CUSTOMER'] });
    await placeOrder();
    const minted = storedCart().checkoutKey;
    await userEvent.click(screen.getByRole('button', { name: 'Place order' }));
    expect(await screen.findByText(/Nothing was ordered twice/)).toBeInTheDocument();
    expect(screen.getByText('abcdef12')).toBeInTheDocument();
    expect(keys).toHaveLength(1);
    expect(storedCart().checkoutKey).toBe(minted);
  });
});
