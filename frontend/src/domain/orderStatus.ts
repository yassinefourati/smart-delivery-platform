import type { OrderStatusView, OrderStatusWire } from '../lib/api/schemas/order';

/**
 * Which order states can still be cancelled.
 *
 * SOURCE OF TRUTH: order-service/src/main/java/com/smartdelivery/order/domain/OrderStatus.java.
 * `isCancellable()` there is `canTransitionTo(CANCELLED)`, and the transition table gives
 * exactly the five `true` rows below. Keeping the rule in one predicate beside the state list
 * is what stops the Cancel button and the order tracker from disagreeing about it.
 *
 * `satisfies Record<OrderStatusWire, boolean>` makes an eleventh backend status a compile error
 * here rather than a silent `undefined` -- and `undefined` would be falsy, which fails safe, but
 * a table that quietly stops covering the state machine is still a table nobody can trust.
 *
 * Once a shipment exists (SHIPMENT_CREATED onwards) the order has to go through the delivery
 * workflow instead; cancelling then returns 409 CONFLICT. The UI does not offer a button that
 * is going to fail -- and still handles losing the race, because the status it holds is up to
 * one poll interval stale.
 */
const CANCELLABLE = {
  CREATED: true,
  INVENTORY_RESERVATION_PENDING: true,
  INVENTORY_RESERVED: true,
  PAYMENT_PENDING: true,
  PAID: true,
  SHIPMENT_CREATED: false,
  OUT_FOR_DELIVERY: false,
  DELIVERED: false,
  CANCELLED: false,
  FAILED: false,
} satisfies Record<OrderStatusWire, boolean>;

/** `UNKNOWN` is never cancellable: offering an action on a state we cannot read is a guess. */
export function canCancel(status: OrderStatusView): boolean {
  return status === 'UNKNOWN' ? false : CANCELLABLE[status];
}

/**
 * At PAID a cancel also triggers a refund (ADR 008's compensation), so the button and the
 * confirmation say so rather than letting a customer discover it afterwards.
 */
export function cancelInvolvesRefund(status: OrderStatusView): boolean {
  return status === 'PAID';
}

const TERMINAL = new Set<OrderStatusView>(['DELIVERED', 'CANCELLED', 'FAILED']);

/** Nothing further will ever happen to an order in one of these states. */
export function isTerminal(status: OrderStatusView): boolean {
  return TERMINAL.has(status);
}

/**
 * The saga's own chain. Everything after PAID -> SHIPMENT_CREATED advances on HUMAN action
 * (an admin assigning an agent, the agent completing the delivery -- OrderSagaEventHandler),
 * which takes hours or days, not seconds.
 */
const SAGA_IN_FLIGHT = new Set<OrderStatusView>([
  'CREATED',
  'INVENTORY_RESERVATION_PENDING',
  'INVENTORY_RESERVED',
  'PAYMENT_PENDING',
]);

export function isSagaInFlight(status: OrderStatusView): boolean {
  return SAGA_IN_FLIGHT.has(status);
}
