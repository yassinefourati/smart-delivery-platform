import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import {
  LOGIN_RESPONSE,
  PRODUCT_PAGE_RESPONSE,
  PROBLEM_401,
} from '../../../lib/api/__tests__/fixtures';
import {
  http,
  jsonResponse,
  problemResponse,
  server,
  setupTestServer,
} from '../../../lib/api/__tests__/testServer';
import { renderApp } from '../../../test/renderApp';

setupTestServer();

beforeEach(() => {
  localStorage.clear();
  sessionStorage.clear();
  server.use(
    http.post('/api/v1/auth/login', () => jsonResponse({ ...LOGIN_RESPONSE, roles: ['CUSTOMER'] })),
    http.get('/api/v1/users/:userId/addresses', () => jsonResponse([])),
    // "/" is the catalog, where a refused ?next= lands.
    http.get('/api/v1/products', () => jsonResponse(PRODUCT_PAGE_RESPONSE)),
    http.get('/api/v1/categories', () => jsonResponse([])),
  );
});

async function signIn() {
  await userEvent.type(await screen.findByLabelText('Email'), 'a@example.test');
  await userEvent.type(screen.getByLabelText('Password'), 'password123');
  await userEvent.click(screen.getByRole('button', { name: 'Sign in' }));
}

describe('login', () => {
  it('returns to ?next= after signing in', async () => {
    const { router } = renderApp('/login?next=%2Faccount%2Faddresses');
    await signIn();
    await waitFor(() => expect(router.state.location.pathname).toBe('/account/addresses'));
  });

  it('refuses an off-site ?next= (open redirect) and goes home instead', async () => {
    const { router } = renderApp('/login?next=%2F%2Fevil.example');
    await signIn();
    await waitFor(() => expect(router.state.location.pathname).toBe('/'));
  });

  it('does not say which half of the credential was wrong', async () => {
    server.use(http.post('/api/v1/auth/login', () => problemResponse(401, PROBLEM_401)));
    renderApp('/login');
    await signIn();
    expect(
      await screen.findByText('That email and password do not match an account.'),
    ).toBeInTheDocument();
  });
});

describe('route guards', () => {
  it('sends a signed-out visitor to login with the page they wanted', async () => {
    const { router } = renderApp('/orders');
    await waitFor(() => expect(router.state.location.pathname).toBe('/login'));
    expect(new URLSearchParams(router.state.location.search).get('next')).toBe('/orders');
  });

  it('shows a customer "Not allowed" on an admin page, and keeps them signed in', async () => {
    renderApp('/admin/products', { signedInAs: ['CUSTOMER'] });
    expect(await screen.findByRole('heading', { name: 'Not allowed' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });

  it('lets a WAREHOUSE_MANAGER into warehouses (inventory-service allows it) but not products', async () => {
    server.use(http.get('/api/v1/warehouses', () => jsonResponse([])));
    const view = renderApp('/admin/warehouses', { signedInAs: ['WAREHOUSE_MANAGER'] });
    expect(await screen.findByRole('heading', { name: 'Warehouses' })).toBeInTheDocument();
    await view.router.navigate('/admin/products');
    expect(await screen.findByRole('heading', { name: 'Not allowed' })).toBeInTheDocument();
  });

  it('never signs anyone out on a 403', async () => {
    server.use(
      http.get('/api/v1/users/:userId', () =>
        problemResponse(403, {
          error: 'FORBIDDEN',
          status: 403,
          detail: 'Access denied',
          correlationId: 'f-1',
        }),
      ),
    );
    renderApp('/account', { signedInAs: ['CUSTOMER'] });
    expect(await screen.findByText(/Not allowed\./)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });
});
