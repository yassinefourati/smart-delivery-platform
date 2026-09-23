/**
 * payment-service.
 *
 * EVERY PATH UNDER `/api/v1/payments/**` IS `hasAnyRole("ADMIN","SERVICE")` -- payment-service
 * SecurityConfig:58, and verified live: a CUSTOMER token on
 * `GET /api/v1/payments/order/{orderId}` gets 403 FORBIDDEN. So there is no customer receipt
 * view and no customer charge-status claim anywhere in this client; payment lookup exists only
 * in the admin dispatch console, which is the only place it can. That a customer cannot see
 * their own payment record is named upward as a probable backend gap, not papered over.
 */

import { z } from 'zod';
import { idSchema, instantSchema, moneySchema } from './common';

export const paymentStatusValues = ['PENDING', 'SUCCESS', 'FAILED', 'REFUNDED'] as const;

export const paymentStatusSchema = z.enum(paymentStatusValues);

export type PaymentStatus = (typeof paymentStatusValues)[number];

/**
 * Verified live: `GET /api/v1/payments/order/{orderId}` with an ADMIN token returned
 * `{id, orderId, amount: 50.00, currency: "USD", status: "SUCCESS", createdAt, updatedAt}`.
 *
 * `status` is a STRICT enum here, unlike `OrderStatus`. The difference is deliberate and it is
 * about blast radius: an unrecognised payment status appears on one admin lookup screen, read
 * by a person who can go and look at the database, whereas an unrecognised ORDER status
 * appears on every customer's order page at once. A strict parse that fails loudly is the
 * right answer in the first case and the wrong answer in the second.
 */
export const paymentResponseSchema = z.object({
  id: idSchema,
  orderId: idSchema,
  amount: moneySchema,
  currency: z.string(),
  status: paymentStatusSchema,
  createdAt: instantSchema,
  updatedAt: instantSchema,
});

export type PaymentResponse = z.infer<typeof paymentResponseSchema>;

/* -------------------------------------------------------------------------------------- */
/* Charge and refund -- typed, DELIBERATELY NOT EXPOSED                                    */
/* -------------------------------------------------------------------------------------- */

/**
 * `POST /api/v1/payments` and `POST /api/v1/payments/refund` are reachable to an ADMIN and are
 * still wrong to put behind a button, so neither appears in endpoints.ts.
 *
 * The saga charges automatically after reservation, so a manual charge DOUBLE-CHARGES. And
 * cancellation compensation issues refunds off the `order.cancelled` event (ADR 008), so a
 * manual refund button RACES the compensation listener and double-refunds. The schemas are
 * here because they are part of the contract this file documents.
 */
export const chargeRequestSchema = z.object({
  orderId: idSchema,
  amount: moneySchema,
  currency: z.string().optional(),
});

export type ChargeRequest = z.infer<typeof chargeRequestSchema>;

export const refundRequestSchema = z.object({
  orderId: idSchema,
});

export type RefundRequest = z.infer<typeof refundRequestSchema>;
