/**
 * product-service: the catalog. Products and categories.
 *
 * Reads here are `permitAll()`, which is what gives this app a genuinely useful anonymous
 * surface -- and that in turn is what makes the memory-only access token affordable, because
 * after a page refresh the user is anonymous rather than broken.
 */

import { z } from 'zod';
import { idSchema, instantSchema, moneySchema, pageSchema } from './common';
import type { PageQuery } from './common';

/* -------------------------------------------------------------------------------------- */
/* Categories                                                                              */
/* -------------------------------------------------------------------------------------- */

export const categoryRequestSchema = z.object({
  name: z.string(),
  description: z.string().optional(),
});

export type CategoryRequest = z.infer<typeof categoryRequestSchema>;

/**
 * `description` is `.nullable()`, verified twice over: a category created without one returns
 * `"description":null`, AND -- the case that actually bites -- `PUT /api/v1/categories/{id}`
 * IS A FULL REPLACE, so a PUT that omits `description` NULLS A STORED VALUE. Verified live:
 * PUT with only `{name}` on a category that had a description answered
 * `"description":null`.
 *
 * That is a note for whoever builds the admin category form: load the current values into the
 * form and send them all back. A form that sends only the changed field silently erases the
 * rest.
 */
export const categoryResponseSchema = z.object({
  id: idSchema,
  name: z.string(),
  description: z.string().nullable(),
});

export type CategoryResponse = z.infer<typeof categoryResponseSchema>;

export const categoryListSchema = z.array(categoryResponseSchema);

/* -------------------------------------------------------------------------------------- */
/* Products                                                                                */
/* -------------------------------------------------------------------------------------- */

export const productRequestSchema = z.object({
  sku: z.string(),
  name: z.string(),
  description: z.string().optional(),
  price: moneySchema,
  /**
   * Kept in the request even though the storefront does not render remote images.
   *
   * DROPPING IT WOULD BE DESTRUCTIVE, not merely tidy: `PUT /api/v1/products/{id}` is a full
   * replace, so a form without this field NULLS a stored `imageUrl` on every save. The CSP
   * stays `img-src 'self' data:` and the storefront draws a deterministic SKU tile instead,
   * because `imageUrl` is an arbitrary absolute URL an admin typed -- there is no upload
   * endpoint, no validation, no size or aspect contract, and rendering it would also leak a
   * referrer to a third party. Enabling it later is one CSP entry plus
   * `referrerpolicy="no-referrer"`.
   */
  imageUrl: z.string().optional(),
  /**
   * DEFAULTS TO FALSE ON THE SERVER, not true. Verified live: creating a product without
   * `active` returned `"active":false`, i.e. a product invisible to any screen that filters on
   * it. The admin create form must send `active` explicitly or every new product lands
   * disabled and the author has no idea why.
   */
  active: z.boolean().optional(),
  categoryId: idSchema,
});

export type ProductRequest = z.infer<typeof productRequestSchema>;

/**
 * Verified live against `GET /api/v1/products`, `GET /api/v1/products/{id}`,
 * `POST /api/v1/products` and `PUT /api/v1/products/{id}`.
 *
 * TWO NULLABILITY FACTS THAT COST A SCREEN EACH IF YOU GET THEM WRONG:
 *
 * 1. `imageUrl` is null on every seeded product and on everything this repository creates.
 *    Nothing populates it. So it is `.nullable()`, not optional-and-null conflated.
 *
 * 2. `createdAt` AND `updatedAt` ARE NULL ON THE 201 FROM `POST /api/v1/products`. This is the
 *    single most expensive thing verified while writing this file, because it is invisible
 *    from any GET: the create response is serialised before the JPA audit timestamps are read
 *    back, so the POST answers `"createdAt":null,"updatedAt":null` while an immediate GET of
 *    the SAME id answers real instants. Declaring them non-null -- which the api-reference's
 *    `string:date-time` and plain reading both suggest -- makes the admin "create product"
 *    screen throw CONTRACT_VIOLATION on every SUCCESSFUL create, and the bug looks like a
 *    failed create when the row is actually there.
 */
export const productResponseSchema = z.object({
  id: idSchema,
  sku: z.string(),
  name: z.string(),
  description: z.string().nullable(),
  price: moneySchema,
  imageUrl: z.string().nullable(),
  active: z.boolean(),
  categoryId: idSchema,
  categoryName: z.string().nullable(),
  createdAt: instantSchema.nullable(),
  updatedAt: instantSchema.nullable(),
});

export type ProductResponse = z.infer<typeof productResponseSchema>;

export const productPageResponseSchema = pageSchema(productResponseSchema);

export type ProductPageResponse = z.infer<typeof productPageResponseSchema>;

/**
 * The catalog query, which is also the catalog's URL.
 *
 * All seven members live in `useSearchParams` rather than React state, so a filtered list is
 * linkable and the back button works. BUG PREVENTED: filters held in component state, where
 * a reload or a back navigation silently discards what the user chose.
 *
 * `search`, `categoryId`, `minPrice` and `maxPrice` ARE in the OpenAPI document; `page`,
 * `size` and `sort` are not, and work (see `PageQuery` in ./common).
 */
export interface ProductListQuery extends PageQuery {
  readonly categoryId?: string | undefined;
  readonly minPrice?: number | undefined;
  readonly maxPrice?: number | undefined;
  readonly search?: string | undefined;
}
