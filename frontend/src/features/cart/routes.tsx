import type { RouteObject } from 'react-router';

export const cartRoutes: RouteObject[] = [
  { path: 'cart', lazy: async () => ({ Component: (await import('./CartPage')).CartPage }) },
];
