import type { RouteObject } from 'react-router';

/** Composed under <RequireRole roles={['ADMIN']}> by src/routes.tsx. */
export const adminDispatchRoutes: RouteObject[] = [
  {
    path: 'admin/shipments',
    lazy: async () => ({ Component: (await import('./ShipmentsPage')).ShipmentsPage }),
  },
  {
    path: 'admin/agents',
    lazy: async () => ({ Component: (await import('./AgentsPage')).AgentsPage }),
  },
  {
    path: 'admin/orders',
    lazy: async () => ({ Component: (await import('./OrderLookupPage')).OrderLookupPage }),
  },
];
