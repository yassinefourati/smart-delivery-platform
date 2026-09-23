import type { RouteObject } from 'react-router';

/** Composed under <RequireRole roles={['ADMIN']}> by src/routes.tsx. */
export const adminCatalogRoutes: RouteObject[] = [
  {
    path: 'admin/products',
    lazy: async () => ({ Component: (await import('./ProductsAdminPage')).ProductsAdminPage }),
  },
  {
    path: 'admin/products/new',
    lazy: async () => ({ Component: (await import('./ProductFormPage')).ProductFormPage }),
  },
  {
    path: 'admin/products/:productId',
    lazy: async () => ({ Component: (await import('./ProductFormPage')).ProductFormPage }),
  },
  {
    path: 'admin/categories',
    lazy: async () => ({ Component: (await import('./CategoriesAdminPage')).CategoriesAdminPage }),
  },
];
