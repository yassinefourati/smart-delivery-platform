import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import { useEffect, useState, type ReactNode } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';

import { http, HttpResponse, server } from '../lib/api/__tests__/testServer';
import { AuthProvider, useSession } from '../lib/auth/AuthProvider';
import { CartProvider } from '../lib/cart/CartProvider';
import { appRoutes } from '../routes';

export const TEST_USER_ID = '11111111-1111-4111-8111-111111111111';

/**
 * Renders the REAL route tree -- shell, guards, lazy pages -- at `path`, with a fresh Query
 * cache and no retries (a test that waits out a backoff is testing the clock).
 *
 * `signedInAs` signs in through the real AuthProvider.login against an MSW login handler, so
 * the token path under test is the production one; there is no back door into the session.
 */
export function renderApp(path: string, options: { signedInAs?: string[] } = {}) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity }, mutations: { retry: false } },
  });
  const router = createMemoryRouter(appRoutes, { initialEntries: [path] });
  if (options.signedInAs) {
    const roles = options.signedInAs;
    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({
          accessToken: 'test-token',
          tokenType: 'Bearer',
          expiresInSeconds: 3600,
          userId: TEST_USER_ID,
          roles,
        }),
      ),
    );
  }
  const utils = render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <CartProvider>
          <SignedIn when={Boolean(options.signedInAs)}>
            <RouterProvider router={router} />
          </SignedIn>
        </CartProvider>
      </AuthProvider>
    </QueryClientProvider>,
  );
  return { ...utils, router, queryClient };
}

function SignedIn({ when, children }: { when: boolean; children: ReactNode }) {
  const { login } = useSession();
  const [ready, setReady] = useState(!when);
  useEffect(() => {
    if (!when) return;
    let live = true;
    void login('test@example.com', 'password123').then(() => {
      if (live) setReady(true);
    });
    return () => {
      live = false;
    };
  }, [when, login]);
  return ready ? <>{children}</> : null;
}
