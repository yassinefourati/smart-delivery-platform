import { QueryClientProvider } from '@tanstack/react-query';
import { RouterProvider, createBrowserRouter } from 'react-router';

import { ErrorBoundary } from './components/ErrorBoundary';
import { queryClient } from './lib/api/queryClient';
import { AuthProvider } from './lib/auth/AuthProvider';
import { CartProvider } from './lib/cart/CartProvider';
import { appRoutes } from './routes';

/**
 * The provider stack and the router.
 *
 * Two React contexts exist -- session and cart -- because once TanStack Query owns server
 * state, that is all the client state there is. Anything fetched lives in the Query cache and
 * is never copied into a context.
 *
 * ORDER MATTERS:
 *   ErrorBoundary        -- above the router, so a crash in routing itself still renders
 *   QueryClientProvider  -- above anything that fetches, including the auth provider's login
 *   AuthProvider         -- above CartProvider: logout clears the cart and its idempotency key
 *   CartProvider
 *   RouterProvider       -- innermost: every route may read session and cart
 *
 * The router and the client are module-level: created once, never per render.
 */
const router = createBrowserRouter(appRoutes);

export default function App() {
  return (
    <ErrorBoundary>
      <QueryClientProvider client={queryClient}>
        <AuthProvider>
          <CartProvider>
            <RouterProvider router={router} />
          </CartProvider>
        </AuthProvider>
      </QueryClientProvider>
    </ErrorBoundary>
  );
}
