import type { RouteObject } from 'react-router';

/** Composed under <RequireRole roles={['DELIVERY_AGENT', 'ADMIN']}> by src/routes.tsx. */
export const agentRoutes: RouteObject[] = [
  {
    path: 'agent/deliveries',
    lazy: async () => ({ Component: (await import('./DeliveriesPage')).DeliveriesPage }),
  },
  {
    path: 'agent/deliveries/:deliveryId',
    lazy: async () => ({ Component: (await import('./DeliveryPage')).DeliveryPage }),
  },
];
