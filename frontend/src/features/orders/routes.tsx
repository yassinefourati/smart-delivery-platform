import type { RouteObject } from 'react-router';

export const orderRoutes: RouteObject[] = [
  { path: 'orders', lazy: async () => ({ Component: (await import('./OrdersPage')).OrdersPage }) },
  {
    path: 'orders/:orderId',
    lazy: async () => ({ Component: (await import('./OrderPage')).OrderPage }),
  },
];
