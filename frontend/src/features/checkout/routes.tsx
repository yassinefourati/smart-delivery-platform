import type { RouteObject } from 'react-router';

export const checkoutRoutes: RouteObject[] = [
  {
    path: 'checkout',
    lazy: async () => ({ Component: (await import('./CheckoutPage')).CheckoutPage }),
  },
];
