import { Outlet, type RouteObject } from 'react-router';

import { AppShell } from './components/AppShell';
import { Loading } from './components/Loading';
import { NotFoundPage, RouteErrorPage } from './components/StatusPages';
import { SupportPage } from './components/SupportPage';
import { accountRoutes, publicAccountRoutes } from './features/account/routes';
import { adminCatalogRoutes } from './features/admin/catalog/routes';
import { adminDispatchRoutes } from './features/admin/dispatch/routes';
import { adminStockRoutes } from './features/admin/stock/routes';
import { adminUserRoutes } from './features/admin/users/routes';
import { agentRoutes } from './features/agent/routes';
import { cartRoutes } from './features/cart/routes';
import { catalogRoutes } from './features/catalog/routes';
import { checkoutRoutes } from './features/checkout/routes';
import { orderRoutes } from './features/orders/routes';
import { RequireAuth, RequireRole } from './lib/auth/guards';

/**
 * The whole route tree, in one place, so "who can see what" is readable top to bottom.
 *
 * Each feature exports a RouteObject[] whose pages load through the router's `lazy`, so the
 * chunk boundary is the page and importing an array here costs a few hundred bytes. That is
 * also why this is the ONE file allowed to import from features/admin/** and features/agent/**
 * (eslint.config.js): a customer never downloads staff pages unless they navigate to one.
 *
 * The guards wrap GROUPS, and every group has its own errorElement, so a broken admin screen
 * leaves the shell, the nav and the cart intact. Guards are user experience; the server
 * enforces every one of these rules itself (see lib/auth/guards.tsx).
 */
export const appRoutes: RouteObject[] = [
  {
    element: <AppShell />,
    errorElement: <RouteErrorPage />,
    hydrateFallbackElement: <Loading label="Loading" />,
    children: [
      {
        errorElement: <RouteErrorPage />,
        children: [
          ...catalogRoutes,
          ...cartRoutes,
          ...publicAccountRoutes,
          { path: 'support', element: <SupportPage /> },
        ],
      },
      {
        errorElement: <RouteErrorPage />,
        element: (
          <RequireAuth>
            <Outlet />
          </RequireAuth>
        ),
        children: [...checkoutRoutes, ...orderRoutes, ...accountRoutes],
      },
      {
        // Products and categories: product-service permits GET to everyone and requires
        // ADMIN for every write.
        errorElement: <RouteErrorPage />,
        element: (
          <RequireRole roles={['ADMIN']}>
            <Outlet />
          </RequireRole>
        ),
        children: [...adminCatalogRoutes, ...adminDispatchRoutes, ...adminUserRoutes],
      },
      {
        // inventory-service's MANAGED_ROLES: gating on ADMIN alone would lock a warehouse
        // manager out of screens the server would let them use.
        errorElement: <RouteErrorPage />,
        element: (
          <RequireRole roles={['ADMIN', 'WAREHOUSE_MANAGER']}>
            <Outlet />
          </RequireRole>
        ),
        children: adminStockRoutes,
      },
      {
        errorElement: <RouteErrorPage />,
        element: (
          <RequireRole roles={['DELIVERY_AGENT', 'ADMIN']}>
            <Outlet />
          </RequireRole>
        ),
        children: agentRoutes,
      },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
];
