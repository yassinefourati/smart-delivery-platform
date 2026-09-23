/**
 * order-service: order placement, tracking and cancellation. The most decision-bearing
 * schemas in this client.
 *
 * WHAT THIS FILE OWNS: the WIRE enum and the two gates that keep it honest. What it
 * deliberately does NOT own: the RULES. `canCancel`, `isTerminal` and the poll-delay tiers
 * live in src/domain/ as pure functions that mirror named Java files, because those are the
 * three places a bug is expensive and they must be testable without a schema, a fetch or a
 * rendered tree.
 */

import { z } from 'zod';
import { countSchema, idSchema, instantSchema, moneySchema, pageSchema } from './common';
import type { PageQuery } from './common';

/* -------------------------------------------------------------------------------------- */
/* The order state machine, on the wire                                                    */
/* -------------------------------------------------------------------------------------- */

/**
 * The ten statuses, IN SAGA ORDER, transcribed from
 * order-service/src/main/java/com/smartdelivery/order/domain/OrderStatus.java.
 *
 * The order of this array is load-bearing -- it is the order of the customer-facing timeline
 * and the order `ORDER_STATUS_SAGA_POSITION` below is derived from. CANCELLED and FAILED are
 * last because they are exits, not steps.
 *
 * WHERE THE SAGA ACTUALLY ENDS, because this is the fact most clients get wrong:
 * `OrderStatus.ALLOWED_TRANSITIONS` gives the saga's own chain as CREATED ->
 * INVENTORY_RESERVATION_PENDING -> INVENTORY_RESERVED -> PAYMENT_PENDING -> PAID ->
 * SHIPMENT_CREATED, and it stops there. `OrderSagaEventHandler:97` advances
 * SHIPMENT_CREATED -> OUT_FOR_DELIVERY on a `DeliveryAssigned` event, which is an ADMIN
 * calling `POST /api/v1/shipments/{id}/assign`; line 102 advances OUT_FOR_DELIVERY ->
 * DELIVERED on `DeliveryCompleted`, which is an AGENT calling
 * `POST /api/v1/deliveries/{id}/complete`. Those last two steps are human workflow measured in
 * hours or days. Interval-polling them is a load generator, not a feature -- which is why
 * `nextPollDelay` in src/domain/polling.ts returns null at SHIPMENT_CREATED.
 */
export const orderStatusValues = [
  'CREATED',
  'INVENTORY_RESERVATION_PENDING',
  'INVENTORY_RESERVED',
  'PAYMENT_PENDING',
  'PAID',
  'SHIPMENT_CREATED',
  'OUT_FOR_DELIVERY',
  'DELIVERED',
  'CANCELLED',
  'FAILED',
] as const;

/** A status the server is known to send today. `UNKNOWN` is NOT a member -- see below. */
export type OrderStatusWire = (typeof orderStatusValues)[number];

/**
 * A status as the UI may have to RENDER it: the ten, plus `UNKNOWN`.
 *
 * `UNKNOWN` is a member of the PRESENTATION union and NOT of the wire union, so the two can
 * never be confused: nothing can build a request body containing `UNKNOWN`, and
 * `orderStatusWireSchema` rejects it as input (there is a test).
 */
export type OrderStatusView = OrderStatusWire | 'UNKNOWN';

/** The value an unrecognised status degrades to. Exported so tests and `statusMeta` agree. */
export const UNKNOWN_ORDER_STATUS = 'UNKNOWN' as const;

/**
 * The STRICT schema. Rejects anything outside the ten, including the literal `'UNKNOWN'`.
 *
 * Used for request-shaped and test-shaped assertions, never at the fetch boundary.
 */
export const orderStatusWireSchema = z.enum(orderStatusValues);

/**
 * THE BOUNDARY SCHEMA, and the reason it is written this way rather than as
 * `z.enum(orderStatusValues).catch('UNKNOWN')`.
 *
 * `.catch(v)` in zod 4 requires `v` to be assignable to the enum's output type, so the literal
 * form the plan sketches needs an `as never` cast to compile -- a lie in the one file that
 * exists to stop lies about the wire. This form is the same behaviour, fully typed, no cast.
 *
 * WHY `z.string()` AND NOT `z.unknown()` AS THE INPUT: the two failures must be told apart.
 *   - An UNRECOGNISED STRING is the additive enum change this repository's own compatibility
 *     rule explicitly permits ("add fields, never rename or remove" -- docs/security.md,
 *     docs/kafka-events.md). A bundle already in a browser must survive it, so it degrades to
 *     `UNKNOWN`, renders "In progress -- step unknown", polls at 15s to the ceiling and hides
 *     the Cancel button. An `assertNever` here would instead throw on a real customer's order
 *     page, which is the worse failure and is why the compile gate alone is not enough.
 *   - A MISSING, NULL OR NON-STRING `status` is not an additive change, it is a BREAK. Under
 *     `z.unknown()` it would silently render "step unknown" forever; under `z.string()` it
 *     fails at the fetch with a field path, which is what a break deserves.
 *
 * The compile-time half of the pair is `ORDER_STATUS_SAGA_POSITION` below, plus
 * `satisfies Record<OrderStatusWire, ...>` on `canCancel`'s table in src/domain/orderStatus.ts
 * and on `statusMeta` in features/orders/. Both gates, not one: `satisfies` costs nothing and
 * catches an omitted status in CI, and the runtime catch protects the bundle CI never sees
 * again.
 */
export const orderStatusSchema = z.string().transform((raw): OrderStatusView => {
  const parsed = orderStatusWireSchema.safeParse(raw);
  return parsed.success ? parsed.data : UNKNOWN_ORDER_STATUS;
});

/**
 * Each wire status' position in the saga chain, and THE COMPILE GATE.
 *
 * The `satisfies Record<OrderStatusWire, number>` is the whole point: add an eleventh value to
 * `orderStatusValues` and this object fails to typecheck, so `npm run typecheck` in CI names
 * the omission before release. It does nothing for a bundle already in a browser -- that is
 * `orderStatusSchema`'s job -- and neither gate substitutes for the other.
 *
 * CANCELLED and FAILED share position 99 because they are not a later step than DELIVERED;
 * they are exits from wherever the order was. Do not use this to decide whether something is
 * finished -- `isTerminal` in src/domain/orderStatus.ts mirrors `OrderStatus.isTerminal()` and
 * is the answer to that question.
 */
export const ORDER_STATUS_SAGA_POSITION = {
  CREATED: 0,
  INVENTORY_RESERVATION_PENDING: 1,
  INVENTORY_RESERVED: 2,
  PAYMENT_PENDING: 3,
  PAID: 4,
  SHIPMENT_CREATED: 5,
  OUT_FOR_DELIVERY: 6,
  DELIVERED: 7,
  CANCELLED: 99,
  FAILED: 99,
} satisfies Record<OrderStatusWire, number>;

/** Narrowing helper for the places that hold an `OrderStatusView` and need the wire union. */
export function isOrderStatusWire(value: string): value is OrderStatusWire {
  return (orderStatusValues as readonly string[]).includes(value);
}

/* -------------------------------------------------------------------------------------- */
/* Requests                                                                                */
/* -------------------------------------------------------------------------------------- */

export const orderItemRequestSchema = z.object({
  productId: idSchema,
  /** Mirrors `OrderItemRequest`'s `@Min(1)`. The server is the authority; this is UX. */
  quantity: countSchema.min(1),
});

export type OrderItemRequest = z.infer<typeof orderItemRequestSchema>;

/**
 * The body of `POST /api/v1/orders`.
 *
 * IT IS ALSO THE IDEMPOTENCY FINGERPRINT. `OrderService.reconcileReplay` compares a SHA-256
 * `RequestFingerprint` of this body against the one stored with the `Idempotency-Key`, so ANY
 * change to `shippingAddressId` or to `items` -- including a quantity -- must ROTATE the key.
 * Verified live: the same key with `quantity` changed from 2 to 3 returned 409. That is why
 * `src/domain/idempotency.ts` rotates on a body change and why the key is persisted in the
 * cart blob beside the very fields it fingerprints.
 */
export const createOrderRequestSchema = z.object({
  shippingAddressId: idSchema,
  items: z.array(orderItemRequestSchema).min(1),
});

export type CreateOrderRequest = z.infer<typeof createOrderRequestSchema>;

/* -------------------------------------------------------------------------------------- */
/* Responses                                                                               */
/* -------------------------------------------------------------------------------------- */

export const orderItemResponseSchema = z.object({
  productId: idSchema,
  productName: z.string().nullable(),
  unitPrice: moneySchema,
  quantity: countSchema,
  lineTotal: moneySchema,
});

export type OrderItemResponse = z.infer<typeof orderItemResponseSchema>;

/**
 * Verified live on the 201 from `POST /api/v1/orders`, on `GET /api/v1/orders/{id}`, inside
 * `OrderPageResponse.content`, and on the 200 from `POST /api/v1/orders/{id}/cancel`.
 *
 * `totalAmount` IS THE AUTHORITATIVE AMOUNT from the 201 onward: `OrderService.buildOrder`
 * snapshots the SERVER's price at creation, so the cart's own sum is an estimate until this
 * field exists and must be labelled as one.
 *
 * WHAT THIS RESPONSE DOES NOT CARRY, and what the FAILED screen therefore must not claim:
 * there is no `failureReason` and no `previousStatus`. Combined with `/api/v1/payments/**`
 * being `hasAnyRole("ADMIN","SERVICE")` (verified: a customer token gets 403), a customer
 * CANNOT be told why their order failed or whether they were charged. The FAILED copy says
 * what is true and stops -- it does not say "you have not been charged" and it does not say
 * "any payment has been refunded automatically", because the client cannot observe either.
 *
 * `status` is parsed through `orderStatusSchema`, so this type's `status` is
 * `OrderStatusView` -- and that is intentional: every consumer of an order is a renderer, and
 * the one place that must not accept `UNKNOWN` is a request body, which uses the wire schema.
 */
export const orderResponseSchema = z.object({
  id: idSchema,
  userId: idSchema,
  status: orderStatusSchema,
  shippingAddressId: idSchema,
  totalAmount: moneySchema,
  items: z.array(orderItemResponseSchema),
  createdAt: instantSchema,
  updatedAt: instantSchema,
});

export type OrderResponse = z.infer<typeof orderResponseSchema>;

/**
 * The polling response, and the ONLY thing polled on an interval.
 *
 * Two fields, so it is cheap enough to ask for every second during the saga's first ten
 * seconds. The full order is fetched once per observed TRANSITION, never on the interval --
 * polling `GET /api/v1/orders/{id}` instead would drag the items, the address and the totals
 * across the wire to learn one word.
 */
export const orderStatusResponseSchema = z.object({
  orderId: idSchema,
  status: orderStatusSchema,
});

export type OrderStatusResponse = z.infer<typeof orderStatusResponseSchema>;

export const orderPageResponseSchema = pageSchema(orderResponseSchema);

export type OrderPageResponse = z.infer<typeof orderPageResponseSchema>;

/**
 * The query for `GET /api/v1/orders/user/{userId}`.
 *
 * The OpenAPI document declares a `required: true` OBJECT parameter named `pageable`, which a
 * generated client would serialise as `?pageable=%5Bobject+Object%5D`. What the endpoint
 * actually accepts is flat `page`/`size`/`sort` -- verified live with `?page=0&size=5`
 * returning a well-formed `OrderPageResponse`.
 */
export type OrderListQuery = PageQuery;
