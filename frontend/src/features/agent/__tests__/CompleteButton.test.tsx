import { fireEvent, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import {
  http,
  jsonResponse,
  problemResponse,
  server,
  setupTestServer,
} from '../../../lib/api/__tests__/testServer';
import { renderApp } from '../../../test/renderApp';

setupTestServer();

const now = new Date().toISOString();
const delivery = {
  id: 'e3b0c442-98fc-4c14-9afb-f4c8996fb924',
  shipmentId: 's-1',
  agentId: 'a-1',
  status: 'ASSIGNED',
  assignedAt: now,
  deliveredAt: null,
};
let completes = 0;

beforeEach(() => {
  localStorage.clear();
  completes = 0;
  server.use(http.get('/api/v1/deliveries/:deliveryId', () => jsonResponse(delivery)));
});

describe('completing a delivery', () => {
  it('asks first, then posts once and announces the result', async () => {
    server.use(
      http.post('/api/v1/deliveries/:deliveryId/complete', () => {
        completes += 1;
        return jsonResponse({ ...delivery, status: 'COMPLETED', deliveredAt: now });
      }),
    );
    renderApp(`/agent/deliveries/${delivery.id}`, { signedInAs: ['DELIVERY_AGENT'] });
    await userEvent.click(await screen.findByRole('button', { name: 'Mark as delivered' }));
    expect(completes).toBe(0);
    await userEvent.click(screen.getByRole('button', { name: 'Yes, delivered' }));
    expect(await screen.findByText(/Delivery marked complete/)).toBeInTheDocument();
    expect(completes).toBe(1);
  });

  it('sends one completion for a same-frame double tap', async () => {
    server.use(
      http.post('/api/v1/deliveries/:deliveryId/complete', () => {
        completes += 1;
        return jsonResponse({ ...delivery, status: 'COMPLETED', deliveredAt: now });
      }),
    );
    renderApp(`/agent/deliveries/${delivery.id}`, { signedInAs: ['DELIVERY_AGENT'] });
    await userEvent.click(await screen.findByRole('button', { name: 'Mark as delivered' }));
    const confirm = screen.getByRole('button', { name: 'Yes, delivered' });
    fireEvent.click(confirm);
    fireEvent.click(confirm);
    expect(await screen.findByText(/Delivery marked complete/)).toBeInTheDocument();
    expect(completes).toBe(1);
  });

  it('says it was already complete on a 409', async () => {
    server.use(
      http.post('/api/v1/deliveries/:deliveryId/complete', () =>
        problemResponse(409, { error: 'CONFLICT', status: 409, detail: 'x', correlationId: 'c' }),
      ),
    );
    renderApp(`/agent/deliveries/${delivery.id}`, { signedInAs: ['DELIVERY_AGENT'] });
    await userEvent.click(await screen.findByRole('button', { name: 'Mark as delivered' }));
    await userEvent.click(screen.getByRole('button', { name: 'Yes, delivered' }));
    expect(
      await screen.findByText('This delivery was already marked complete.'),
    ).toBeInTheDocument();
  });

  it('shows not-allowed on a 403 and keeps the agent signed in', async () => {
    server.use(
      http.post('/api/v1/deliveries/:deliveryId/complete', () =>
        problemResponse(403, {
          error: 'FORBIDDEN',
          status: 403,
          detail: 'not yours',
          correlationId: 'f',
        }),
      ),
    );
    renderApp(`/agent/deliveries/${delivery.id}`, { signedInAs: ['DELIVERY_AGENT'] });
    await userEvent.click(await screen.findByRole('button', { name: 'Mark as delivered' }));
    await userEvent.click(screen.getByRole('button', { name: 'Yes, delivered' }));
    expect(await screen.findByText(/Not allowed\./)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });
});
