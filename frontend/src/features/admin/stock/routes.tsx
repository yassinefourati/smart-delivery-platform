import type { RouteObject } from 'react-router';

/** Composed under <RequireRole roles={['ADMIN', 'WAREHOUSE_MANAGER']}> by src/routes.tsx. */
export const adminStockRoutes: RouteObject[] = [
  {
    path: 'admin/warehouses',
    lazy: async () => ({ Component: (await import('./WarehousesPage')).WarehousesPage }),
  },
  {
    path: 'admin/stock',
    lazy: async () => ({ Component: (await import('./StockPage')).StockPage }),
  },
  {
    path: 'admin/stock/:productId',
    lazy: async () => ({ Component: (await import('./StockPage')).StockPage }),
  },
];
