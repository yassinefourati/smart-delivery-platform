import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import {
  http,
  jsonResponse,
  problemResponse,
  server,
  setupTestServer,
} from '../../../lib/api/__tests__/testServer';
import { freshOrder } from '../../../test/apiHandlers';
import { renderApp } from '../../../test/renderApp';

setupTestServer();

let status = 'PAID';
let statusCalls = 0;
const order = freshOrder('PAID');

beforeEach(() => {
  localStorage.clear();
  status = 'PAID';
  statusCalls = 0;
  server.use(
    http.get('/api/v1/orders/:orderId', () => jsonResponse({ ...order, status })),
    http.get('/api/v1/orders/:orderId/status', () => {
      statusCalls += 1;
      return jsonResponse({ orderId: order.id, status });
    }),
  );
});

describe('the order page', () => {
  it('offers "Cancel and request refund" at PAID', async () => {
    renderApp(`/orders/${order.id}`, { signedInAs: ['CUSTOMER'] });
    expect(
      await screen.findByRole('button', { name: 'Cancel and request refund' }),
    ).toBeInTheDocument();
    expect(screen.getByText('Payment taken. Preparing your shipment.')).toBeInTheDocument();
  });

  it('turns a lost cancel race into a sentence derived from the FRESH status, never the raw detail', async () => {
    server.use(
      http.post('/api/v1/orders/:orderId/cancel', () => {
        status = 'SHIPMENT_CREATED';
        return problemResponse(409, {
          error: 'CONFLICT',
          status: 409,
          detail: 'Cannot move an order from SHIPMENT_CREATED to CANCELLED',
          correlationId: 'c-409',
        });
      }),
    );
    renderApp(`/orders/${order.id}`, { signedInAs: ['CUSTOMER'] });
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel and request refund' }));
    // The dialog re-reads the status on open before offering the button.
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel and refund' }));
    expect(
      await screen.findByText(
        "This order shipped just before your cancellation went through, so it wasn't cancelled.",
      ),
    ).toBeInTheDocument();
    expect(screen.queryByText(/Cannot move an order/)).not.toBeInTheDocument();
    await waitFor(() =>
      expect(
        screen.queryByRole('button', { name: /Cancel and request refund/ }),
      ).not.toBeInTheDocument(),
    );
  });

  it('swaps the dialog to an explanation when the fresh status on open is no longer cancellable', async () => {
    renderApp(`/orders/${order.id}`, { signedInAs: ['CUSTOMER'] });
    const button = await screen.findByRole('button', { name: 'Cancel and request refund' });
    status = 'OUT_FOR_DELIVERY';
    await userEvent.click(button);
    expect(await screen.findByText(/shipped just before your cancellation/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Cancel and refund' })).not.toBeInTheDocument();
  });

  it('shows the exact FAILED copy, which does not claim anything about payment', async () => {
    status = 'FAILED';
    renderApp(`/orders/${order.id}`, { signedInAs: ['CUSTOMER'] });
    expect(
      await screen.findByText(/We couldn't complete this order and it has been cancelled/),
    ).toBeInTheDocument();
    expect(screen.queryByText(/not been charged/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/refunded automatically/i)).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Cancel/ })).not.toBeInTheDocument();
  });

  it.each(['DELIVERED', 'CANCELLED', 'FAILED', 'SHIPMENT_CREATED'])(
    'does not keep polling at %s',
    async (s) => {
      status = s;
      renderApp(`/orders/${order.id}`, { signedInAs: ['CUSTOMER'] });
      await waitFor(() => expect(statusCalls).toBeGreaterThanOrEqual(1));
      const settled = statusCalls;
      await new Promise((r) => setTimeout(r, 1_500));
      expect(statusCalls).toBe(settled);
    },
  );

  it('keeps polling while the saga is in flight', async () => {
    status = 'PAYMENT_PENDING';
    renderApp(`/orders/${order.id}`, { signedInAs: ['CUSTOMER'] });
    await waitFor(() => expect(statusCalls).toBeGreaterThanOrEqual(2), { timeout: 3_000 });
  });
});
