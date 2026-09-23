import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import {
  INVENTORY_SUMMARY_EMPTY,
  PRODUCT_PAGE_RESPONSE,
} from '../../../lib/api/__tests__/fixtures';
import { http, jsonResponse, server, setupTestServer } from '../../../lib/api/__tests__/testServer';
import { catalogReads, seedCart } from '../../../test/apiHandlers';
import { renderApp } from '../../../test/renderApp';

setupTestServer();

let productQueries: string[] = [];

beforeEach(() => {
  localStorage.clear();
  productQueries = [];
  server.use(
    ...catalogReads,
    http.get('/api/v1/products', ({ request: incoming }) => {
      productQueries.push(new URL(incoming.url).search);
      return jsonResponse(PRODUCT_PAGE_RESPONSE);
    }),
  );
});

describe('the catalog', () => {
  it('renders signed out, with no remote images', async () => {
    renderApp('/');
    expect(await screen.findAllByRole('link', { name: 'Smoke Widget' })).toHaveLength(2);
    expect(document.querySelector('img')).toBeNull();
    expect(screen.getByRole('link', { name: 'Sign in' })).toBeInTheDocument();
  });

  it('keeps filters in the URL, so they are linkable and Back restores them', async () => {
    const { router } = renderApp('/');
    await screen.findAllByRole('link', { name: 'Smoke Widget' });
    await userEvent.type(screen.getByLabelText('Search'), 'widget');
    await userEvent.click(screen.getByRole('button', { name: 'Apply' }));
    await waitFor(() => expect(router.state.location.search).toBe('?search=widget'));
    await waitFor(() => expect(productQueries.at(-1)).toContain('search=widget'));
    await router.navigate(-1);
    await waitFor(() => expect(router.state.location.search).toBe(''));
    await waitFor(() => expect(screen.getByLabelText('Search')).toHaveValue(''));
  });
});

describe('the cart', () => {
  it('blocks checkout and says how many are left when stock is short', async () => {
    server.use(
      http.get('/api/v1/inventory/:productId', () =>
        jsonResponse({ ...INVENTORY_SUMMARY_EMPTY, totalAvailable: 1 }),
      ),
    );
    seedCart();
    renderApp('/cart');
    expect(await screen.findByText('Only 1 left')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Go to checkout' })).toBeDisabled();
  });

  it('blocks checkout on a changed price until it is accepted', async () => {
    seedCart({
      lines: [
        {
          productId: PRODUCT_PAGE_RESPONSE.content[0].id,
          sku: 'S',
          nameAtAdd: 'Smoke Widget',
          priceAtAdd: 20,
          quantity: 1,
        },
      ],
    });
    renderApp('/cart');
    expect(await screen.findByText(/Price changed: was/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Go to checkout' })).toBeDisabled();
    await userEvent.click(screen.getByRole('button', { name: 'Accept new prices' }));
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Go to checkout' })).toBeEnabled(),
    );
  });
});
