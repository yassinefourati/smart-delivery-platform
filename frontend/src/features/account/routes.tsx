import type { RouteObject } from 'react-router';

/** Public: reachable signed out. */
export const publicAccountRoutes: RouteObject[] = [
  { path: 'login', lazy: async () => ({ Component: (await import('./LoginPage')).LoginPage }) },
  {
    path: 'register',
    lazy: async () => ({ Component: (await import('./RegisterPage')).RegisterPage }),
  },
];

/** Composed under <RequireAuth> by src/routes.tsx. */
export const accountRoutes: RouteObject[] = [
  {
    path: 'account',
    lazy: async () => ({ Component: (await import('./ProfilePage')).ProfilePage }),
  },
  {
    path: 'account/addresses',
    lazy: async () => ({ Component: (await import('./AddressesPage')).AddressesPage }),
  },
];
