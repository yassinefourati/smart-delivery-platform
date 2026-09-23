/**
 * Every schema against the response it was written from.
 *
 * These are not tests of zod. They are tests that each schema says what the RUNNING API says --
 * which is the only property that matters, because a schema that is stricter than the wire turns a
 * successful call into a blank screen, and one that is looser lets a renamed field reach a render.
 * Each case that looks trivial is a nullability fact that was verified with curl.
 */

import { describe, expect, it } from 'vitest';
import { instantSchema, pageSchema, problemDetailSchema } from '../schemas/common';
import {
  addressListSchema,
  addressResponseSchema,
  isHumanRole,
  loginResponseSchema,
  userResponseSchema,
} from '../schemas/user';
import {
  categoryResponseSchema,
  productPageResponseSchema,
  productResponseSchema,
} from '../schemas/product';
import {
  inventoryResponseSchema,
  inventorySummaryResponseSchema,
  warehouseResponseSchema,
} from '../schemas/inventory';
import {
  ORDER_STATUS_SAGA_POSITION,
  UNKNOWN_ORDER_STATUS,
  createOrderRequestSchema,
  isOrderStatusWire,
  orderPageResponseSchema,
  orderResponseSchema,
  orderStatusResponseSchema,
  orderStatusSchema,
  orderStatusValues,
  orderStatusWireSchema,
} from '../schemas/order';
import { paymentResponseSchema } from '../schemas/payment';
import {
  agentResponseSchema,
  deliveryResponseSchema,
  shipmentResponseSchema,
} from '../schemas/delivery';
import { z } from 'zod';
import {
  ADDRESS_RESPONSE,
  AGENT_RESPONSE,
  CATEGORY_AFTER_PARTIAL_PUT,
  CATEGORY_RESPONSE,
  DELIVERY_RESPONSE_ASSIGNED,
  DELIVERY_RESPONSE_COMPLETED,
  INVENTORY_RESPONSE,
  INVENTORY_SUMMARY_EMPTY,
  INVENTORY_SUMMARY_IN_STOCK,
  LOGIN_RESPONSE,
  ORDER_PAGE_RESPONSE,
  ORDER_RESPONSE_CANCELLED,
  ORDER_RESPONSE_CREATED,
  ORDER_STATUS_RESPONSE,
  PAYMENT_RESPONSE,
  PRODUCT_CREATED_201,
  PRODUCT_PAGE_RESPONSE,
  PRODUCT_RESPONSE,
  PROBLEM_401,
  SHIPMENT_RESPONSE,
  USER_RESPONSE,
  USER_RESPONSE_NO_PHONE,
  WAREHOUSE_RESPONSE,
} from './fixtures';

describe('common primitives', () => {
  it('accepts both the nanosecond and microsecond instants this platform sends', () => {
    // Nine fractional digits from POST /shipments/{id}/assign, six from a later GET of the same
    // record. A precision-opinionated validator would reject real traffic.
    expect(instantSchema.safeParse('2026-09-23T04:08:43.982909487Z').success).toBe(true);
    expect(instantSchema.safeParse('2026-09-23T04:08:43.982909Z').success).toBe(true);
    // The platform's non-problem gateway 404 uses an offset rather than Z.
    expect(instantSchema.safeParse('2026-09-23T04:05:05.448+00:00').success).toBe(true);
  });

  it('rejects a string the UI would render as "Invalid Date"', () => {
    // The check is exactly the operation the UI performs, so a value that would show the literal
    // text "Invalid Date" on a screen fails at the fetch instead.
    expect(instantSchema.safeParse('yesterday').success).toBe(false);
    expect(instantSchema.safeParse('').success).toBe(false);
  });

  it('parses the platform problem body, and tolerates a partial one', () => {
    expect(problemDetailSchema.parse(PROBLEM_401)).toMatchObject({
      error: 'UNAUTHORIZED',
      title: 'UNAUTHORIZED',
      correlationId: PROBLEM_401.correlationId,
    });
    // Every member optional, on purpose: code that throws while building an error erases the
    // failure it was describing.
    expect(problemDetailSchema.safeParse({}).success).toBe(true);
  });

  it('strips unknown keys rather than failing on an added field', () => {
    // The platform's own compatibility rule is "add fields, never rename or remove". A strict
    // object would make every additive server change a CONTRACT_VIOLATION on a live screen.
    const parsed = productResponseSchema.parse({ ...PRODUCT_RESPONSE, brandNewField: 'hello' });
    expect(parsed).not.toHaveProperty('brandNewField');
    expect(parsed.sku).toBe(PRODUCT_RESPONSE.sku);
  });

  it('builds a page envelope with the five fields both paged endpoints return', () => {
    const schema = pageSchema(z.object({ id: z.string() }));
    expect(
      schema.parse({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
    ).toEqual({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    // Not Spring Data's default Page JSON: no `pageable`, no `sort`, no `first`/`last`.
    expect(schema.safeParse({ content: [], number: 0, totalElements: 0 }).success).toBe(false);
  });
});

describe('user-service', () => {
  it('parses a real LoginResponse', () => {
    const parsed = loginResponseSchema.parse(LOGIN_RESPONSE);
    expect(parsed.tokenType).toBe('Bearer');
    expect(parsed.expiresInSeconds).toBe(3600);
    expect(parsed.roles).toEqual(['ADMIN']);
  });

  it('does not lock a user out when a fifth role appears', () => {
    // `roles` is a capability test, never a switch. A strict enum here would turn "the platform
    // added a role" into a failed LOGIN for a bundle already in a browser.
    const parsed = loginResponseSchema.parse({
      ...LOGIN_RESPONSE,
      roles: ['CUSTOMER', 'AUDITOR'],
    });
    expect(parsed.roles).toContain('AUDITOR');
    expect(isHumanRole('AUDITOR')).toBe(false);
    expect(isHumanRole('WAREHOUSE_MANAGER')).toBe(true);
  });

  it('parses a user with and without a phone number', () => {
    expect(userResponseSchema.parse(USER_RESPONSE).phoneNumber).toBe('+15550100');
    // The key is PRESENT and null when registration omitted it -- never absent.
    expect(userResponseSchema.parse(USER_RESPONSE_NO_PHONE).phoneNumber).toBeNull();
    const { phoneNumber: _absent, ...missingKey } = USER_RESPONSE_NO_PHONE;
    expect(userResponseSchema.safeParse(missingKey).success).toBe(false);
  });

  it('parses an address whose optional state came back null', () => {
    expect(addressResponseSchema.parse(ADDRESS_RESPONSE).state).toBeNull();
    expect(addressListSchema.parse([ADDRESS_RESPONSE])).toHaveLength(1);
  });
});

describe('product-service', () => {
  it('parses a paged catalog with every imageUrl null', () => {
    const parsed = productPageResponseSchema.parse(PRODUCT_PAGE_RESPONSE);
    expect(parsed.totalPages).toBe(2);
    expect(parsed.content.every((product) => product.imageUrl === null)).toBe(true);
    // `price` arrives through res.json() as a JSON number. Any claim to keep it as a string "to
    // avoid float error" is false by the time this line runs.
    expect(parsed.content[0]?.price).toBe(25);
  });

  /**
   * THE MOST EXPENSIVE ASSERTION IN THIS FILE.
   *
   * The 201 from `POST /api/v1/products` carries `createdAt: null` and `updatedAt: null`, while an
   * immediate GET of the same id carries real instants. Nothing in the OpenAPI document hints at
   * it. Non-null types here would make the admin create screen throw CONTRACT_VIOLATION on every
   * SUCCESSFUL create, and the failure would look like a failed create.
   */
  it('parses the 201 from a product create, whose audit timestamps are null', () => {
    const parsed = productResponseSchema.parse(PRODUCT_CREATED_201);
    expect(parsed.createdAt).toBeNull();
    expect(parsed.updatedAt).toBeNull();
    // Also real, and also a trap: `active` defaults to FALSE when the request omits it.
    expect(parsed.active).toBe(false);
  });

  it('parses a category whose description a full-replace PUT nulled', () => {
    expect(categoryResponseSchema.parse(CATEGORY_RESPONSE).description).toBe(
      'Created by the smoke test',
    );
    expect(categoryResponseSchema.parse(CATEGORY_AFTER_PARTIAL_PUT).description).toBeNull();
  });
});

describe('inventory-service', () => {
  it('parses the public summary for a product with no stock at all', () => {
    // The response that lets the cart block a doomed order. 200 with no token -- permitAll().
    const parsed = inventorySummaryResponseSchema.parse(INVENTORY_SUMMARY_EMPTY);
    expect(parsed.totalAvailable).toBe(0);
    // An empty array, never a missing key.
    expect(parsed.warehouses).toEqual([]);
  });

  it('parses the summary for a product that has stock', () => {
    const parsed = inventorySummaryResponseSchema.parse(INVENTORY_SUMMARY_IN_STOCK);
    expect(parsed.totalAvailable).toBe(50);
    expect(parsed.warehouses[0]?.warehouseName).toBe('Contract Depot 1790136250');
  });

  it('parses a warehouse and a single inventory row', () => {
    expect(warehouseResponseSchema.parse(WAREHOUSE_RESPONSE).active).toBe(true);
    expect(inventoryResponseSchema.parse(INVENTORY_RESPONSE).reservedQuantity).toBe(0);
  });
});

describe('order-service -- the order state machine', () => {
  it('lists the ten statuses in saga order', () => {
    expect(orderStatusValues).toHaveLength(10);
    expect(orderStatusValues[0]).toBe('CREATED');
    // The saga's own chain ends at PAID -> SHIPMENT_CREATED. The two after it advance on human
    // action, which is why interval polling stops there.
    expect(orderStatusValues.indexOf('SHIPMENT_CREATED')).toBeLessThan(
      orderStatusValues.indexOf('OUT_FOR_DELIVERY'),
    );
  });

  /**
   * THE COMPILE GATE, asserted at runtime as well so that deleting it is visible.
   *
   * `ORDER_STATUS_SAGA_POSITION` closes with `satisfies Record<OrderStatusWire, number>`, so adding
   * an eleventh value to `orderStatusValues` without adding it here fails `npm run typecheck`. That
   * does nothing for a bundle already in a browser -- which is the runtime catch's job -- and
   * neither gate substitutes for the other.
   */
  it('has a position for every wire status, and no extras', () => {
    expect(Object.keys(ORDER_STATUS_SAGA_POSITION).sort()).toEqual([...orderStatusValues].sort());
  });

  it('parses every one of the ten statuses through the boundary schema', () => {
    for (const status of orderStatusValues) {
      expect(orderStatusSchema.parse(status)).toBe(status);
    }
  });

  /**
   * The additive enum change this repository's compatibility rule explicitly permits. A deployed
   * bundle must survive it: `assertNever` here would throw on a real customer's order page.
   */
  it('degrades an unrecognised status to UNKNOWN instead of throwing', () => {
    expect(orderStatusSchema.parse('AWAITING_CUSTOMS')).toBe(UNKNOWN_ORDER_STATUS);
    expect(orderStatusSchema.parse('unknown')).toBe(UNKNOWN_ORDER_STATUS);
  });

  it('fails a MISSING or non-string status, because that is a break and not an addition', () => {
    // The distinction the boundary schema exists to draw. Under a looser input type these would
    // silently render "In progress -- step unknown" forever.
    expect(orderStatusSchema.safeParse(undefined).success).toBe(false);
    expect(orderStatusSchema.safeParse(null).success).toBe(false);
    expect(orderStatusSchema.safeParse(5).success).toBe(false);
  });

  it('keeps UNKNOWN out of the wire union', () => {
    // A presentation value, never a wire value: nothing can build a request containing it.
    expect(orderStatusWireSchema.safeParse(UNKNOWN_ORDER_STATUS).success).toBe(false);
    expect(isOrderStatusWire('UNKNOWN')).toBe(false);
    expect(isOrderStatusWire('PAID')).toBe(true);
  });

  it('parses an order response at creation, after the saga, and after a cancel', () => {
    expect(orderResponseSchema.parse(ORDER_RESPONSE_CREATED).status).toBe('CREATED');
    expect(orderResponseSchema.parse(ORDER_RESPONSE_CANCELLED).status).toBe('CANCELLED');
    expect(orderResponseSchema.parse(ORDER_RESPONSE_CREATED).totalAmount).toBe(50);
  });

  it('parses a status response and a page of orders', () => {
    expect(orderStatusResponseSchema.parse(ORDER_STATUS_RESPONSE).status).toBe('SHIPMENT_CREATED');
    expect(orderPageResponseSchema.parse(ORDER_PAGE_RESPONSE).totalElements).toBe(1);
  });

  it('refuses an order with no lines and a quantity below one', () => {
    // Mirrors `OrderItemRequest`'s @Min(1). The server is the authority; this is UX that stops a
    // request the server would reject anyway.
    expect(createOrderRequestSchema.safeParse({ shippingAddressId: 'a', items: [] }).success).toBe(
      false,
    );
    expect(
      createOrderRequestSchema.safeParse({
        shippingAddressId: 'a',
        items: [{ productId: 'p', quantity: 0 }],
      }).success,
    ).toBe(false);
    expect(
      createOrderRequestSchema.safeParse({
        shippingAddressId: 'a',
        items: [{ productId: 'p', quantity: 1 }],
      }).success,
    ).toBe(true);
  });
});

describe('payment-service and delivery-service', () => {
  it('parses an admin payment lookup', () => {
    const parsed = paymentResponseSchema.parse(PAYMENT_RESPONSE);
    expect(parsed.status).toBe('SUCCESS');
    expect(parsed.currency).toBe('USD');
  });

  it('fails loudly on an unknown payment status, unlike an order status', () => {
    // Deliberate asymmetry, and it is about blast radius: an unknown PAYMENT status reaches one
    // admin lookup screen read by somebody who can go and look at the database, while an unknown
    // ORDER status reaches every customer's order page at once.
    expect(
      paymentResponseSchema.safeParse({ ...PAYMENT_RESPONSE, status: 'CHARGEBACK' }).success,
    ).toBe(false);
  });

  it('parses a shipment and an agent', () => {
    expect(shipmentResponseSchema.parse(SHIPMENT_RESPONSE).status).toBe('CREATED');
    expect(agentResponseSchema.parse(AGENT_RESPONSE).userId).toBe(AGENT_RESPONSE.userId);
  });

  it('parses a delivery before and after completion', () => {
    // `deliveredAt` is null for the whole time the delivery is actually in an agent's hands, which
    // is the only time the agent app looks at it.
    expect(deliveryResponseSchema.parse(DELIVERY_RESPONSE_ASSIGNED).deliveredAt).toBeNull();
    expect(deliveryResponseSchema.parse(DELIVERY_RESPONSE_COMPLETED).deliveredAt).not.toBeNull();
    expect(deliveryResponseSchema.parse(DELIVERY_RESPONSE_COMPLETED).status).toBe('COMPLETED');
  });
});
