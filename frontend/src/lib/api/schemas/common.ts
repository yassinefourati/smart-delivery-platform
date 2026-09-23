/**
 * Primitives every service's schemas are built from, plus the two shapes that are not any
 * single service's: RFC 7807's problem body and Spring's page envelope.
 *
 * WHY SCHEMAS AT ALL, AND WHY HAND-WRITTEN. The six live OpenAPI documents declare NO
 * `required` array on ANY response schema, so a generated client types every field as
 * `T | undefined` and the codebase fills with non-null assertions that assert something the
 * document never promised. These schemas instead say what the RUNNING API actually does,
 * field by field, checked with curl -- and `z.infer` means the schema IS the type, so there
 * is one artifact to maintain rather than a schema plus an interface that can disagree.
 *
 * WHY `z.object` AND NOT `z.looseObject`/`z.strictObject`. `z.object` STRIPS unknown keys.
 * That is exactly the platform's own compatibility rule ("add fields, never rename or
 * remove" -- docs/security.md, docs/kafka-events.md): a server that adds a field must not
 * break a bundle already in a browser, and `z.strictObject` would make every additive server
 * change a CONTRACT_VIOLATION on a real customer's screen. A REMOVED or RENAMED field still
 * fails, with a field path, at the fetch -- which is the half that matters.
 */

import { z } from 'zod';

/**
 * An identifier as it arrives on the wire.
 *
 * DELIBERATELY NOT `z.uuid()`, although every id this API returns today is a UUID. Nothing in
 * this client parses, compares or generates the INTERNALS of a server id -- ids are opaque
 * strings that go back out in a path segment. A format assertion here would buy nothing and
 * would turn a server that legitimately changed its id scheme (a ULID, a prefixed key) into a
 * blank page on every screen at once. `z.string().min(1)` still catches the two failures that
 * matter: the field is gone, or it is not a string.
 */
export const idSchema = z.string().min(1);

/**
 * An ISO-8601 instant, validated by whether it actually parses.
 *
 * `Date.parse` and not `z.iso.datetime()`: the wire carries NANOSECOND precision on some
 * responses and microsecond on others -- `POST /api/v1/shipments/{id}/assign` answered
 * `"assignedAt":"2026-09-23T04:08:43.982909487Z"` (nine fractional digits) while a later GET
 * of the same record answered `"2026-09-23T04:08:43.982909Z"` (six). Both are legal ISO-8601
 * and both are what this platform sends, so a precision-opinionated validator would reject
 * real traffic. What we DO care about is that the string reaches `new Date(...)` intact,
 * because the alternative is the literal text "Invalid Date" rendered on a screen -- so the
 * check is exactly the operation the UI will perform.
 */
export const instantSchema = z.string().refine((value) => Number.isFinite(Date.parse(value)), {
  message: 'not a parseable ISO-8601 instant',
});

/**
 * A monetary amount, as a JSON number.
 *
 * STATED HONESTLY, BECAUSE THE OPPOSITE CLAIM IS COMMON AND FALSE: `res.json()` has already
 * turned `"price":25.00` into the IEEE-754 double `25` by the time this schema runs, so
 * declaring the field a string here to "avoid float error" would not avoid anything -- the
 * precision was decided by the JSON parser, not by us. The real control is architectural:
 * `OrderService.buildOrder` snapshots the SERVER's price when the order is created, so this
 * client never computes an authoritative amount. A cart sum is labelled an estimate, and
 * `OrderResponse.totalAmount` is authoritative from the 201 onward.
 */
export const moneySchema = z.number();

/** A count of items. Non-negative integers on every endpoint that returns one. */
export const countSchema = z.number().int();

/**
 * RFC 7807 problem body, as this platform actually emits it.
 *
 * TEN fields, and the last five are duplicates of the first five by design:
 * `platform-starter`'s `ApiErrors.of` populates RFC 7807's own members (`type`, `title`,
 * `status`, `detail`, `instance`) AND writes back the five properties clients have read since
 * Phase 2 (`timestamp`, `error`, `message`, `path`, `correlationId`) as extension properties,
 * so the new body is a strict superset of the old one. `title` always equals `error` and
 * `detail` always equals `message`. That redundancy is the migration, and dropping it is
 * documented as a breaking change.
 *
 * EVERY FIELD IS OPTIONAL HERE, WHICH IS NOT LAZINESS. This schema is used inside the error
 * path, and code that throws while building an error is the worst kind of bug to debug: the
 * original failure disappears. `parseProblem` runs this with `safeParse` and degrades to a
 * synthesised problem, so a truncated or partial body still produces something a user can
 * quote a support code from.
 *
 * NOTE WHAT THIS SCHEMA IS **NOT** USED FOR: deciding whether a response IS a problem. That
 * decision is made on the media type, in problem.ts, before this schema is reached -- because
 * the gateway's own 404 for an unrouted path is `application/json` carrying
 * `{"error":"Not Found"}`, and `"Not Found"` would parse cleanly here and produce a bogus
 * stable code. Verified live: `GET http://localhost:8080/nope` returns exactly that.
 */
export const problemDetailSchema = z.object({
  type: z.string().optional(),
  title: z.string().optional(),
  status: z.number().int().optional(),
  detail: z.string().optional(),
  instance: z.string().optional(),
  timestamp: z.string().optional(),
  error: z.string().optional(),
  message: z.string().optional(),
  path: z.string().optional(),
  correlationId: z.string().optional(),
});

export type ProblemDetailBody = z.infer<typeof problemDetailSchema>;

/**
 * Spring's page envelope, as the platform's two paged endpoints return it.
 *
 * It is NOT Spring Data's default `Page` JSON (no `pageable`, no `sort`, no `first`/`last`,
 * no `numberOfElements`): order-service and product-service both map into a five-field
 * response record. Written as a function rather than a fixed schema because the item type
 * differs, and `z.infer` of the result gives the fully-typed page with no second declaration.
 *
 * `totalElements` is an int64 on the wire. It arrives through `res.json()` as a double, which
 * is exact to 2^53 -- a row count this platform will not reach -- so `z.number().int()` is
 * honest rather than approximate here.
 */
export function pageSchema<TItem extends z.ZodType>(item: TItem) {
  return z.object({
    content: z.array(item),
    page: countSchema,
    size: countSchema,
    totalElements: countSchema,
    totalPages: countSchema,
  });
}

/**
 * The query shape both paged endpoints accept.
 *
 * `page`, `size` and `sort` are ABSENT FROM EVERY OPENAPI DOCUMENT and they work anyway.
 * `ProductController:53` annotates its `Pageable` with `@Parameter(hidden = true)`, and
 * order-service declares `pageable` as a `required: true` OBJECT query parameter that a
 * generated client would serialise as `?pageable=%5Bobject+Object%5D`. Verified live:
 * `GET /api/v1/products?page=0&size=2&sort=price,desc` returns 200 with `totalPages: 2`.
 * This is the single clearest reason there is no codegen in this project -- the catalog's
 * primary interaction is not in the document a generator would read.
 */
export interface PageQuery {
  /** Zero-based. */
  readonly page?: number | undefined;
  readonly size?: number | undefined;
  /** Spring's `property,direction` form, e.g. `price,desc`. */
  readonly sort?: string | undefined;
}
