import type { OrderStatusView } from '../../lib/api/schemas/order';

/**
 * Six customer-legible milestones instead of ten raw saga states. Nobody placing an order
 * needs to know what INVENTORY_RESERVED means; they need to know what is happening and
 * whether they have to do anything.
 */
export const MILESTONES = [
  'Placed',
  'Reserving stock',
  'Taking payment',
  'Preparing shipment',
  'On its way',
  'Delivered',
] as const;

export interface StatusMeta {
  /** Index of the milestone being worked on; everything before it is done. -1 for the terminal failures. */
  readonly active: number;
  readonly label: string;
  /** One line, announced politely when the status CHANGES (not on every poll). */
  readonly headline: string;
}

/**
 * THE RULE THIS TABLE ENCODES: a *_PENDING state never renders its own milestone as done.
 * INVENTORY_RESERVATION_PENDING is "reserving", not "reserved"; PAYMENT_PENDING is "taking
 * payment", not "paid". Showing work as finished before the saga says so is how a customer
 * learns to distrust the whole screen the first time a payment fails.
 *
 * `satisfies Record<OrderStatusView, ...>` makes an eleventh backend status a compile error
 * here, while the schema's UNKNOWN fallback protects a bundle already running in a browser.
 * No percentages, ever: the saga's steps do not take comparable time.
 */
export const STATUS_META = {
  CREATED: { active: 1, label: 'Placed', headline: "Order placed. We're working on it." },
  INVENTORY_RESERVATION_PENDING: {
    active: 1,
    label: 'Reserving stock',
    headline: 'Reserving your items.',
  },
  INVENTORY_RESERVED: {
    active: 2,
    label: 'Stock reserved',
    headline: 'Items reserved. Taking payment next.',
  },
  PAYMENT_PENDING: { active: 2, label: 'Taking payment', headline: 'Taking payment.' },
  PAID: { active: 3, label: 'Paid', headline: 'Payment taken. Preparing your shipment.' },
  SHIPMENT_CREATED: {
    active: 3,
    label: 'Preparing shipment',
    headline: 'Your shipment is ready and waiting for a courier.',
  },
  OUT_FOR_DELIVERY: { active: 4, label: 'Out for delivery', headline: 'Your order is on its way.' },
  DELIVERED: { active: MILESTONES.length, label: 'Delivered', headline: 'Delivered.' },
  CANCELLED: { active: -1, label: 'Cancelled', headline: 'This order was cancelled.' },
  FAILED: {
    active: -1,
    label: 'Could not be completed',
    headline: "We couldn't complete this order.",
  },
  UNKNOWN: {
    active: -1,
    label: 'Updating',
    headline:
      'This order is in a state this page does not recognise yet. Reload to update the app.',
  },
} satisfies Record<OrderStatusView, StatusMeta>;

export type StepState = 'done' | 'active' | 'upcoming';

export function stepState(status: OrderStatusView, index: number): StepState {
  const { active } = STATUS_META[status];
  if (index < active) return 'done';
  if (index === active) return 'active';
  return 'upcoming';
}
