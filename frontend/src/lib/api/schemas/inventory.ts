/**
 * inventory-service: warehouses and stock.
 *
 * TWO PLATFORM FACTS SHAPE EVERYTHING IN THIS FILE.
 *
 * 1. `GET /api/v1/inventory/{productId}` IS `permitAll()` -- inventory-service
 *    SecurityConfig:64, and verified live with no Authorization header at all, returning
 *    `{"productId":"...","totalAvailable":0,"totalReserved":0,"warehouses":[]}`. This is what
 *    lets the UI BLOCK A DOOMED ORDER at the cart instead of explaining a FAILED one it
 *    legally cannot explain. It is not hypothetical: an order placed while verifying this
 *    plan returned 201/CREATED and reached FAILED in about four seconds because
 *    `totalAvailable` was 0.
 *
 * 2. WRITES ACCEPT ADMIN **OR** WAREHOUSE_MANAGER. `MANAGED_ROLES = {ADMIN,
 *    WAREHOUSE_MANAGER}` gates `/api/v1/warehouses/**` and the inventory writes
 *    (SecurityConfig:41,67).
 */

import { z } from 'zod';
import { countSchema, idSchema } from './common';

/* -------------------------------------------------------------------------------------- */
/* Warehouses                                                                              */
/* -------------------------------------------------------------------------------------- */

export const warehouseRequestSchema = z.object({
  name: z.string(),
  location: z.string(),
});

export type WarehouseRequest = z.infer<typeof warehouseRequestSchema>;

export const warehouseResponseSchema = z.object({
  id: idSchema,
  name: z.string(),
  location: z.string(),
  active: z.boolean(),
});

export type WarehouseResponse = z.infer<typeof warehouseResponseSchema>;

export const warehouseListSchema = z.array(warehouseResponseSchema);

/* -------------------------------------------------------------------------------------- */
/* Stock                                                                                   */
/* -------------------------------------------------------------------------------------- */

/**
 * CREATE-ONLY, AND THERE IS NO ADJUST.
 *
 * `POST /api/v1/inventory` creates the FIRST row for a `(productId, warehouseId)` pair.
 * A second call for the same pair throws `InventoryAlreadyExistsException`
 * (`InventoryAdminService:33-42`) and returns 409 -- verified live, with
 * `error: "CONFLICT"` and a detail naming both ids. There is no restock, no adjust and no
 * PUT anywhere in the six documents, so a quantity CANNOT be changed through this API.
 *
 * The stock screen says that in the form rather than pretending otherwise, and the 409 gets
 * specific copy ("this product already has a row at that warehouse") instead of a generic
 * conflict toast.
 */
export const inventoryCreateRequestSchema = z.object({
  productId: idSchema,
  warehouseId: idSchema,
  availableQuantity: countSchema.optional(),
});

export type InventoryCreateRequest = z.infer<typeof inventoryCreateRequestSchema>;

export const inventoryResponseSchema = z.object({
  id: idSchema,
  productId: idSchema,
  warehouseId: idSchema,
  warehouseName: z.string().nullable(),
  availableQuantity: countSchema,
  reservedQuantity: countSchema,
});

export type InventoryResponse = z.infer<typeof inventoryResponseSchema>;

/**
 * The public availability summary. Verified live with and without a token.
 *
 * `warehouses` is `[]` for a product with no stock rows at all, so the empty case is an empty
 * array and never a missing key. `totalAvailable` is ADVISORY, not authoritative: the server
 * is the only arbiter of a reservation, stock can reach zero between this read and the POST,
 * and the UI says so. Prevention here is a probability reduction, not a guarantee.
 */
export const inventorySummaryResponseSchema = z.object({
  productId: idSchema,
  totalAvailable: countSchema,
  totalReserved: countSchema,
  warehouses: z.array(inventoryResponseSchema),
});

export type InventorySummaryResponse = z.infer<typeof inventorySummaryResponseSchema>;

/* -------------------------------------------------------------------------------------- */
/* Reservation lifecycle -- typed, DELIBERATELY NOT EXPOSED                                */
/* -------------------------------------------------------------------------------------- */

/**
 * `POST /api/v1/inventory/reserve|release|deduct` exist and a WAREHOUSE_MANAGER could call
 * them. They are NOT in endpoints.ts, on purpose.
 *
 * They take an `orderId`, which makes them the SAGA'S OWN operations. order-service owns the
 * reservation lifecycle; a hand-released reservation makes an order's recorded state a lie and
 * there is no UI anywhere that could put it back. The schemas are written here because the
 * shapes are part of the contract this file documents, and because a future admin tool that
 * genuinely needs them should start from a verified schema rather than a guess.
 */
export const reservationStatusValues = ['RESERVED', 'RELEASED', 'DEDUCTED'] as const;

export const reservationStatusSchema = z.enum(reservationStatusValues);

export type ReservationStatus = (typeof reservationStatusValues)[number];

export const reserveRequestSchema = z.object({
  orderId: idSchema,
  productId: idSchema,
  quantity: countSchema.optional(),
});

export type ReserveRequest = z.infer<typeof reserveRequestSchema>;

export const reservationLookupRequestSchema = z.object({
  orderId: idSchema,
  productId: idSchema,
});

export type ReservationLookupRequest = z.infer<typeof reservationLookupRequestSchema>;

export const reservationResponseSchema = z.object({
  reservationId: idSchema,
  orderId: idSchema,
  productId: idSchema,
  quantity: countSchema,
  status: reservationStatusSchema,
});

export type ReservationResponse = z.infer<typeof reservationResponseSchema>;
