/**
 * user-service: authentication, the user record, and shipping addresses.
 *
 * SPLIT PER BACKEND SERVICE, NOT PER FEATURE, because that is the axis this repository
 * changes along: when user-service ships a field, exactly one file in this client is touched.
 * A per-feature split would put `AddressResponse` in both `checkout` and `account` and invite
 * two drifting copies of it.
 */

import { z } from 'zod';
import { idSchema, instantSchema } from './common';

/* -------------------------------------------------------------------------------------- */
/* Roles                                                                                   */
/* -------------------------------------------------------------------------------------- */

/**
 * The FOUR human personas, transcribed from
 * user-service/src/main/java/com/smartdelivery/user/domain/RoleName.java.
 *
 * THERE ARE FOUR, NOT THREE. inventory-service gates `/api/v1/warehouses/**` and every
 * inventory write on `MANAGED_ROLES = {ADMIN, WAREHOUSE_MANAGER}` (SecurityConfig:41,67), so a
 * warehouse manager is a real, enforced persona. Gating those screens on ADMIN alone would
 * UX-lock a user out of screens the server would have allowed -- the costliest kind of miss
 * for a client that claims to verify rather than trust.
 */
export const HUMAN_ROLES = ['CUSTOMER', 'ADMIN', 'WAREHOUSE_MANAGER', 'DELIVERY_AGENT'] as const;

export type HumanRole = (typeof HUMAN_ROLES)[number];

/**
 * A role as it arrives in `LoginResponse.roles` or `UserResponse.roles`.
 *
 * `z.string()` AND NOT `z.enum(HUMAN_ROLES)`, deliberately. `RoleName` also has a machine-only
 * SERVICE role, and the platform's compatibility rule permits adding enum values -- so a
 * strict parse here would turn "a fifth role exists" into a failed LOGIN, locking every user
 * out of a bundle already in a browser. Roles are a capability test (`hasRole`), never a
 * switch, so an unrecognised role simply grants nothing. `isHumanRole` below is the narrowing
 * helper for the places that need the union.
 *
 * A browser must NEVER hold SERVICE: it is issued by `POST /api/v1/auth/service-token` against
 * a per-service client secret, and a browser holding that secret is precisely the bug that
 * endpoint's design exists to prevent. Nothing in this client calls it.
 */
export const roleSchema = z.string();

export function isHumanRole(value: string): value is HumanRole {
  return (HUMAN_ROLES as readonly string[]).includes(value);
}

/* -------------------------------------------------------------------------------------- */
/* Authentication                                                                          */
/* -------------------------------------------------------------------------------------- */

export const loginRequestSchema = z.object({
  email: z.string(),
  password: z.string(),
});

export type LoginRequest = z.infer<typeof loginRequestSchema>;

/**
 * Verified live: `{ accessToken, tokenType: "Bearer", expiresInSeconds: 3600, userId,
 * roles: ["ADMIN"] }`.
 *
 * This response is the entire reason there is no JWT library in this project. It already
 * carries `userId`, `roles` and `expiresInSeconds`, so there is nothing to decode -- and
 * decoding a token the browser cannot verify (the resource servers verify against the JWKS)
 * proves nothing while teaching the reflex of treating unverified claims as trusted.
 * `/.well-known/jwks.json` is therefore never called either.
 *
 * `expiresInSeconds` is what `AuthProvider` turns into `expiresAt = Date.now() +
 * expiresInSeconds * 1000 - 30_000`. The 30-second skew is the platform's own number, taken
 * from `ServiceTokenProvider`, not invented.
 */
export const loginResponseSchema = z.object({
  accessToken: z.string().min(1),
  tokenType: z.string(),
  expiresInSeconds: z.number().int(),
  userId: idSchema,
  roles: z.array(roleSchema),
});

export type LoginResponse = z.infer<typeof loginResponseSchema>;

/* -------------------------------------------------------------------------------------- */
/* Users                                                                                   */
/* -------------------------------------------------------------------------------------- */

export const registerUserRequestSchema = z.object({
  email: z.string(),
  password: z.string(),
  firstName: z.string(),
  lastName: z.string(),
  /**
   * Genuinely optional. OMIT THE KEY rather than sending `undefined` -- that is what
   * `exactOptionalPropertyTypes` in tsconfig.json is there to enforce, because
   * `JSON.stringify({ phoneNumber: undefined })` drops the key but `{ phoneNumber: null }`
   * does not, and the two are different requests.
   */
  phoneNumber: z.string().optional(),
});

export type RegisterUserRequest = z.infer<typeof registerUserRequestSchema>;

export const updateUserRequestSchema = z.object({
  firstName: z.string(),
  lastName: z.string(),
  phoneNumber: z.string().optional(),
});

export type UpdateUserRequest = z.infer<typeof updateUserRequestSchema>;

/**
 * Verified live on registration AND on `GET /api/v1/users/{id}`.
 *
 * `phoneNumber` is `.nullable()` and not `.optional()`, and the distinction is not pedantry:
 * registering WITHOUT a phone number returns `"phoneNumber":null` -- the key is present with a
 * null value, never absent. Writing `.optional()` here would type it `string | undefined` and
 * every `phone ?? '--'` in the UI would still work by accident, while a genuine future switch
 * to omitting the key would go unnoticed. Say what the wire does.
 *
 * Registration ALWAYS grants `["CUSTOMER"]` (verified). No endpoint in this API grants any
 * other role, `UpdateUserRequest` carries only the three fields above, and there is no user
 * list or search -- only `GET /api/v1/users/{id}`. That is why this client has no admin user
 * management and no role-granting screen: there is no API to call.
 */
export const userResponseSchema = z.object({
  id: idSchema,
  email: z.string(),
  firstName: z.string(),
  lastName: z.string(),
  phoneNumber: z.string().nullable(),
  active: z.boolean(),
  roles: z.array(roleSchema),
  createdAt: instantSchema,
});

export type UserResponse = z.infer<typeof userResponseSchema>;

/* -------------------------------------------------------------------------------------- */
/* Addresses                                                                               */
/* -------------------------------------------------------------------------------------- */

export const addressRequestSchema = z.object({
  label: z.string(),
  street: z.string(),
  city: z.string(),
  /** Optional on the way in -- omit the key, do not send `undefined`. */
  state: z.string().optional(),
  postalCode: z.string(),
  country: z.string(),
  isDefault: z.boolean().optional(),
});

export type AddressRequest = z.infer<typeof addressRequestSchema>;

/**
 * Verified live: posting an address with no `state` returns `"state":null`.
 *
 * So `state` is `.nullable()` on the way OUT and `.optional()` on the way IN -- the asymmetry
 * is real and writing both as one shared schema would be wrong in one direction. This is the
 * concrete case `exactOptionalPropertyTypes` was turned on for.
 */
export const addressResponseSchema = z.object({
  id: idSchema,
  label: z.string(),
  street: z.string(),
  city: z.string(),
  state: z.string().nullable(),
  postalCode: z.string(),
  country: z.string(),
  isDefault: z.boolean(),
  createdAt: instantSchema,
});

export type AddressResponse = z.infer<typeof addressResponseSchema>;

export const addressListSchema = z.array(addressResponseSchema);
