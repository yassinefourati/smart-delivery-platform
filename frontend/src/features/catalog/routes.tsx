import type { RouteObject } from 'react-router';

/** `lazy` on the route, not React.lazy: the router starts the chunk download on navigation. */
export const catalogRoutes: RouteObject[] = [
  { index: true, lazy: async () => ({ Component: (await import('./CatalogPage')).CatalogPage }) },
  {
    path: 'products/:productId',
    lazy: async () => ({ Component: (await import('./ProductPage')).ProductPage }),
  },
];
