/**
 * delivery-service: shipments, agents and deliveries.
 *
 * READ THIS BEFORE BUILDING ANYTHING ON IT. An agent literally cannot be shown what to
 * deliver, or where, and that was verified link by link rather than assumed:
 *
 *  - `DeliveryResponse` carries `{id, shipmentId, agentId, status, assignedAt, deliveredAt}`
 *    and no address and no order id.
 *  - Resolving `shipmentId` needs `/api/v1/shipments/**`, which is ADMIN-only
 *    (delivery-service SecurityConfig:59; verified live, a CUSTOMER token gets 403).
 *  - `ShipmentResponse` carries no address either -- only `{id, orderId, status, createdAt,
 *    updatedAt}`.
 *  - There is no GET-address-by-id endpoint anywhere in the six documents; addresses are
 *    reachable only as `GET /api/v1/users/{userId}/addresses`, which is owner-or-admin.
 *
 * So the agent app is an honest three-screen worklist -- list, detail, complete -- with no map,
 * no manifest, no route optimiser and no proof-of-delivery capture. The two backend additions
 * that would make it real are named in docs: an `orderId` plus a shipping-address summary on
 * `DeliveryResponse`, or agent-readable access to the shipment.
 *
 * The same constraint kills a CUSTOMER-facing shipment tracking page: a customer cannot fetch
 * their own shipment at all. Customer tracking is the order-status timeline and nothing more.
 */

import { z } from 'zod';
import { idSchema, instantSchema } from './common';

/* -------------------------------------------------------------------------------------- */
/* Shipments                                                                               */
/* -------------------------------------------------------------------------------------- */

export const shipmentStatusValues = ['CREATED', 'ASSIGNED', 'DELIVERED'] as const;

export const shipmentStatusSchema = z.enum(shipmentStatusValues);

export type ShipmentStatus = (typeof shipmentStatusValues)[number];

/**
 * Verified live on `GET /api/v1/shipments`, `GET /api/v1/shipments/{id}` and
 * `GET /api/v1/shipments/order/{orderId}`, all with an ADMIN token.
 *
 * `GET /api/v1/shipments` IS THE ONLY LIST-ISH VIEW OF ORDERS IN THE WHOLE API, and it is why
 * there is no admin order console: by construction it contains only orders that reached
 * SHIPMENT_CREATED, which EXCLUDES exactly the FAILED and stuck population an admin needs to
 * see. A console built on it would look like order management while hiding the stuck sagas,
 * which is worse than not building it. The replacement is an honest lookup by order id, with
 * the limitation stated in the UI and the one endpoint that would fix it named: an ADMIN-only
 * `GET /api/v1/orders?status=&page=`.
 */
export const shipmentResponseSchema = z.object({
  id: idSchema,
  orderId: idSchema,
  status: shipmentStatusSchema,
  createdAt: instantSchema,
  updatedAt: instantSchema,
});

export type ShipmentResponse = z.infer<typeof shipmentResponseSchema>;

export const shipmentListSchema = z.array(shipmentResponseSchema);

export const assignDeliveryRequestSchema = z.object({
  agentId: idSchema,
});

export type AssignDeliveryRequest = z.infer<typeof assignDeliveryRequestSchema>;

/* -------------------------------------------------------------------------------------- */
/* Agents                                                                                  */
/* -------------------------------------------------------------------------------------- */

/**
 * `CreateAgentRequest.userId` is a raw user UUID, typed as text in the admin form.
 *
 * That is not a UX lapse, it is the only thing possible: there is NO user list and NO user
 * search anywhere in this API -- only `GET /api/v1/users/{id}` -- so there is nothing to build
 * a picker from. The form says why, and validates the shape it can.
 *
 * Verified live and worth recording because it is easy to assume otherwise: this endpoint does
 * NOT require the named user to already hold DELIVERY_AGENT. An ADMIN's own userId was
 * accepted and returned 201. A second call for the same userId returns 409 CONFLICT
 * ("a delivery agent is already linked to user ..."), which is the case the form must handle.
 */
export const createAgentRequestSchema = z.object({
  userId: idSchema,
  name: z.string(),
  phone: z.string(),
});

export type CreateAgentRequest = z.infer<typeof createAgentRequestSchema>;

export const agentResponseSchema = z.object({
  id: idSchema,
  userId: idSchema,
  name: z.string(),
  phone: z.string().nullable(),
});

export type AgentResponse = z.infer<typeof agentResponseSchema>;

export const agentListSchema = z.array(agentResponseSchema);

/* -------------------------------------------------------------------------------------- */
/* Deliveries                                                                              */
/* -------------------------------------------------------------------------------------- */

export const deliveryStatusValues = ['ASSIGNED', 'COMPLETED'] as const;

export const deliveryStatusSchema = z.enum(deliveryStatusValues);

export type DeliveryStatus = (typeof deliveryStatusValues)[number];

/**
 * Verified live end to end: created an agent, assigned a shipment (201, `status: "ASSIGNED"`,
 * `deliveredAt: null`), completed it (200, `status: "COMPLETED"`, `deliveredAt` populated), and
 * read it back through `GET /api/v1/deliveries/agent/{userId}`.
 *
 * `deliveredAt` is `.nullable()` -- it is null for the entire time a delivery is actually in an
 * agent's hands, which is the only time the agent app looks at it.
 *
 * `GET /api/v1/deliveries/agent/{userId}` TAKES THE USER ID, NOT THE AGENT ID. The path segment
 * is named `userId` in the document and that is exactly what it means; passing
 * `AgentResponse.id` returns an empty array rather than an error, which is the quietest
 * possible failure and the reason this sentence is here.
 */
export const deliveryResponseSchema = z.object({
  id: idSchema,
  shipmentId: idSchema,
  agentId: idSchema,
  status: deliveryStatusSchema,
  assignedAt: instantSchema,
  deliveredAt: instantSchema.nullable(),
});

export type DeliveryResponse = z.infer<typeof deliveryResponseSchema>;

export const deliveryListSchema = z.array(deliveryResponseSchema);
