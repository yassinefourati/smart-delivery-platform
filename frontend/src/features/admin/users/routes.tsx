import type { RouteObject } from 'react-router';

/** Composed under <RequireRole roles={['ADMIN']}> by src/routes.tsx. */
export const adminUserRoutes: RouteObject[] = [
  {
    path: 'admin/users',
    lazy: async () => ({ Component: (await import('./UsersPage')).UsersPage }),
  },
];
