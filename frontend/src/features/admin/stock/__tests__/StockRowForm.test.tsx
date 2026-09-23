import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import {
  INVENTORY_RESPONSE,
  INVENTORY_SUMMARY_IN_STOCK,
  PRODUCT_RESPONSE,
  WAREHOUSE_RESPONSE,
} from '../../../../lib/api/__tests__/fixtures';
import {
  http,
  jsonResponse,
  problemResponse,
  server,
  setupTestServer,
} from '../../../../lib/api/__tests__/testServer';
import { renderApp } from '../../../../test/renderApp';

setupTestServer();

let posted: unknown[] = [];

beforeEach(() => {
  localStorage.clear();
  posted = [];
  server.use(
    http.get('/api/v1/products/:productId', () => jsonResponse(PRODUCT_RESPONSE)),
    http.get('/api/v1/inventory/:productId', () => jsonResponse(INVENTORY_SUMMARY_IN_STOCK)),
    http.get('/api/v1/warehouses', () => jsonResponse([WAREHOUSE_RESPONSE])),
  );
});

async function addStock() {
  renderApp(`/admin/stock/${PRODUCT_RESPONSE.id}`, { signedInAs: ['WAREHOUSE_MANAGER'] });
  await screen.findByRole('option', { name: WAREHOUSE_RESPONSE.name });
  await userEvent.selectOptions(screen.getByLabelText('Warehouse'), WAREHOUSE_RESPONSE.id);
  await userEvent.clear(screen.getByLabelText('Available quantity'));
  await userEvent.type(screen.getByLabelText('Available quantity'), '40');
  await userEvent.click(screen.getByRole('button', { name: 'Add stock' }));
}

describe('adding stock', () => {
  it('creates the first row for a product and warehouse', async () => {
    server.use(
      http.post('/api/v1/inventory', async ({ request: incoming }) => {
        posted.push(await incoming.json());
        return jsonResponse(INVENTORY_RESPONSE, 201);
      }),
    );
    await addStock();
    expect(await screen.findByText('Stock added.')).toBeInTheDocument();
    expect(posted).toEqual([
      { productId: PRODUCT_RESPONSE.id, warehouseId: WAREHOUSE_RESPONSE.id, availableQuantity: 40 },
    ]);
  });

  it('explains the create-only API on a 409 instead of a generic error', async () => {
    server.use(
      http.post('/api/v1/inventory', () =>
        problemResponse(409, {
          error: 'CONFLICT',
          status: 409,
          detail: 'Inventory already exists',
          correlationId: 'x',
        }),
      ),
    );
    await addStock();
    await waitFor(() =>
      expect(
        screen.getByText(
          'This product already has a stock row in this warehouse. There is no endpoint to change its quantity.',
        ),
      ).toBeInTheDocument(),
    );
  });
});
