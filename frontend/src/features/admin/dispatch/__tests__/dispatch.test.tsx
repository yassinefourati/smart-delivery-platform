import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import {
  http,
  jsonResponse,
  problemResponse,
  server,
  setupTestServer,
} from '../../../../lib/api/__tests__/testServer';
import { freshOrder } from '../../../../test/apiHandlers';
import { renderApp } from '../../../../test/renderApp';

setupTestServer();

const now = new Date().toISOString();
const shipment = {
  id: '0f5c1b2a-1111-4c1e-9d6a-2b2f0c1d3e4f',
  orderId: freshOrder('SHIPMENT_CREATED').id,
  status: 'CREATED',
  createdAt: now,
  updatedAt: now,
};
const agent = {
  id: '7a1e2d3c-2222-4b5a-8c9d-0e1f2a3b4c5d',
  userId: '9b8a7c6d-3333-4e5f-a1b2-c3d4e5f6a7b8',
  name: 'Ada Courier',
  phone: '+15550111',
};

beforeEach(() => {
  localStorage.clear();
  server.use(
    http.get('/api/v1/shipments', () => jsonResponse([shipment])),
    http.get('/api/v1/agents', () => jsonResponse([agent])),
  );
});

describe('shipments', () => {
  it('assigns an agent with {agentId}, which is what sends the order out', async () => {
    let body: unknown;
    server.use(
      http.post('/api/v1/shipments/:shipmentId/assign', async ({ request: incoming }) => {
        body = await incoming.json();
        return jsonResponse({
          id: 'd-1',
          shipmentId: shipment.id,
          agentId: agent.id,
          status: 'ASSIGNED',
          assignedAt: now,
          deliveredAt: null,
        });
      }),
    );
    renderApp('/admin/shipments', { signedInAs: ['ADMIN'] });
    await userEvent.click(await screen.findByRole('button', { name: 'Assign agent' }));
    expect(screen.getByText(/moves to "On its way"/)).toBeInTheDocument();
    await screen.findByRole('option', { name: /Ada Courier/ });
    await userEvent.selectOptions(screen.getByLabelText('Agent'), agent.id);
    await userEvent.click(screen.getByRole('button', { name: 'Assign' }));
    await screen.findByRole('button', { name: 'Assign agent' });
    expect(body).toEqual({ agentId: agent.id });
  });

  it('says so specifically when the shipment was already assigned', async () => {
    server.use(
      http.post('/api/v1/shipments/:shipmentId/assign', () =>
        problemResponse(409, {
          error: 'CONFLICT',
          status: 409,
          detail: 'already assigned',
          correlationId: 'c',
        }),
      ),
    );
    renderApp('/admin/shipments', { signedInAs: ['ADMIN'] });
    await userEvent.click(await screen.findByRole('button', { name: 'Assign agent' }));
    await screen.findByRole('option', { name: /Ada Courier/ });
    await userEvent.selectOptions(screen.getByLabelText('Agent'), agent.id);
    await userEvent.click(screen.getByRole('button', { name: 'Assign' }));
    expect(await screen.findByText(/already been assigned/)).toBeInTheDocument();
  });
});

describe('order lookup', () => {
  it('states that it cannot list all orders, and shows a looked-up order read-only with its payment', async () => {
    const order = freshOrder('PAID');
    server.use(
      http.get('/api/v1/orders/:orderId', () => jsonResponse(order)),
      http.get('/api/v1/payments/order/:orderId', () =>
        jsonResponse({
          id: 'p1234567-0000',
          orderId: order.id,
          amount: 50,
          currency: 'USD',
          status: 'SUCCESS',
          createdAt: now,
          updatedAt: now,
        }),
      ),
      http.get('/api/v1/shipments/order/:orderId', () =>
        problemResponse(404, {
          error: 'NOT_FOUND',
          status: 404,
          detail: 'none',
          correlationId: 'n',
        }),
      ),
    );
    renderApp(`/admin/orders?orderId=${order.id}`, { signedInAs: ['ADMIN'] });
    expect(await screen.findByText(/it cannot list all orders/)).toBeInTheDocument();
    expect(await screen.findByText('SUCCESS')).toBeInTheDocument();
    expect(screen.getByRole('list', { name: 'Order progress' })).toBeInTheDocument();
    expect(screen.getByText('No shipment yet.')).toBeInTheDocument();
    // Read-only: an admin looking at an order is not offered the customer's actions.
    expect(screen.queryByRole('button', { name: /Cancel/ })).not.toBeInTheDocument();
  });
});
