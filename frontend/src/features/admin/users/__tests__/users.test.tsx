import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import {
  http,
  jsonResponse,
  problemResponse,
  server,
  setupTestServer,
} from '../../../../lib/api/__tests__/testServer';
import { renderApp, TEST_USER_ID } from '../../../../test/renderApp';

setupTestServer();

const now = new Date().toISOString();
const courier = {
  id: '9b8a7c6d-3333-4e5f-a1b2-c3d4e5f6a7b8',
  email: 'max@example.com',
  firstName: 'Max',
  lastName: 'Courier',
  phoneNumber: null,
  active: true,
  roles: ['CUSTOMER'],
  createdAt: now,
};

beforeEach(() => {
  localStorage.clear();
});

describe('users and roles', () => {
  it('finds a user by email and grants DELIVERY_AGENT, then offers the agent profile', async () => {
    let lookedUp: string | null = null;
    let granted: string | null = null;
    server.use(
      http.get('/api/v1/users/lookup', ({ request: incoming }) => {
        lookedUp = new URL(incoming.url).searchParams.get('email');
        return jsonResponse(courier);
      }),
      http.put('/api/v1/users/:userId/roles/:role', ({ params }) => {
        granted = `${String(params.userId)}:${String(params.role)}`;
        return jsonResponse({ ...courier, roles: ['CUSTOMER', 'DELIVERY_AGENT'] });
      }),
    );
    renderApp('/admin/users', { signedInAs: ['ADMIN'] });
    await userEvent.type(await screen.findByLabelText('Email'), 'max@example.com');
    await userEvent.click(screen.getByRole('button', { name: 'Find user' }));

    await userEvent.click(await screen.findByRole('button', { name: 'Grant DELIVERY_AGENT' }));
    expect(
      await screen.findByRole('button', { name: 'Revoke DELIVERY_AGENT' }),
    ).toBeInTheDocument();
    expect(lookedUp).toBe('max@example.com');
    expect(granted).toBe(`${courier.id}:DELIVERY_AGENT`);
    expect(screen.getByRole('link', { name: 'create their agent profile' })).toHaveAttribute(
      'href',
      `/admin/agents?userId=${courier.id}`,
    );
    expect(screen.getByText(/next time they sign in/)).toBeInTheDocument();
  });

  it('revokes a role with DELETE', async () => {
    let method: string | null = null;
    server.use(
      http.get('/api/v1/users/lookup', () =>
        jsonResponse({ ...courier, roles: ['CUSTOMER', 'WAREHOUSE_MANAGER'] }),
      ),
      http.delete('/api/v1/users/:userId/roles/:role', ({ request: incoming }) => {
        method = incoming.method;
        return jsonResponse(courier);
      }),
    );
    renderApp('/admin/users?email=max@example.com', { signedInAs: ['ADMIN'] });
    await userEvent.click(await screen.findByRole('button', { name: 'Revoke WAREHOUSE_MANAGER' }));
    expect(
      await screen.findByRole('button', { name: 'Grant WAREHOUSE_MANAGER' }),
    ).toBeInTheDocument();
    expect(method).toBe('DELETE');
  });

  it('does not offer to revoke your own ADMIN', async () => {
    server.use(
      http.get('/api/v1/users/lookup', () =>
        jsonResponse({ ...courier, id: TEST_USER_ID, roles: ['ADMIN'] }),
      ),
    );
    renderApp('/admin/users?email=me@example.com', { signedInAs: ['ADMIN'] });
    expect(await screen.findByText('You cannot revoke your own ADMIN.')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Revoke ADMIN' })).not.toBeInTheDocument();
  });

  it('says plainly when no user has that email', async () => {
    server.use(
      http.get('/api/v1/users/lookup', () =>
        problemResponse(404, {
          error: 'NOT_FOUND',
          status: 404,
          detail: 'none',
          correlationId: 'n',
        }),
      ),
    );
    renderApp('/admin/users?email=nobody@example.com', { signedInAs: ['ADMIN'] });
    expect(
      await screen.findByText('No user has the email nobody@example.com.'),
    ).toBeInTheDocument();
  });

  it('is not reachable without ADMIN', async () => {
    renderApp('/admin/users', { signedInAs: ['CUSTOMER'] });
    expect(await screen.findByRole('heading', { level: 1 })).not.toHaveTextContent(
      'Users and roles',
    );
  });
});
