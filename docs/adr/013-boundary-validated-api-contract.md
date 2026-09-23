# ADR 013: A hand-written API layer, validated at the boundary

## Status
Accepted (Phase 21)

## Context

Every service publishes an OpenAPI document through the gateway (Phase 19), so generating
a TypeScript client was the obvious option. Reading the documents against the running
platform showed the parts of the contract a client most depends on are the parts the
documents get wrong or leave out:

- product-service hides its working `page`, `size` and `sort` parameters
  (`@Parameter(hidden = true)`), and order-service declares its pageable as a required
  *object* parameter a generator would serialise as `?pageable=[object Object]`;
- every `DELETE` documents `200` and answers `204` with no body, and several creates
  document `200` and answer `201`;
- the idempotent replay (`201` with the original id) and the `409` on a changed body are
  behaviour, not schema;
- a `401` from the security filter chain carried `application/problem+json;charset=ISO-8859-1`
  while a `409` carried the bare media type, so an equality check on the content type
  loses every 401 (fixed server-side in commit 4aecb3b; the client stays tolerant);
- `X-Correlation-Id` was emitted twice on every response (same commit).

A generator would type the easy half and leave every one of these to hand-written code
anyway -- and a generated client trusts the response shape it was generated from.

## Decision

**`src/lib/api/` is written by hand, and every response is parsed with a zod schema at
the fetch boundary.** One schema per response type, organised by backend service; the
TypeScript type is inferred from the schema, so there is one artifact, not a type plus a
validator that can drift. A response that fails its schema becomes an `ApiProblem` with
code `CONTRACT_VIOLATION` and a field path -- identically in development and production --
instead of `undefined` three components away. Unknown enum values (a new order status)
degrade to `UNKNOWN` rather than failing, because adding values is a permitted change.

Two scripts cover the drift a boundary parser only catches when a user hits the screen:
`npm run check:contract` diffs the six live documents against committed snapshots, and
`npm run verify:live` asserts the undocumented behaviours above against a running stack.

## Consequences

- Around forty endpoint functions and their schemas are maintained by hand.
- A contract break surfaces as a named, located error, and the support code on screen
  finds the request in the logs.
- `check:contract` reports *that* a document changed, not what to edit; it is a prompt,
  not a fix.

## Reversal

Generation becomes the better trade when the client passes roughly forty response types
or gains a second consumer, or when the documents are made honest (`@PageableAsQueryParam`,
accurate status codes) -- at which point the zod schemas can be generated too, keeping the
boundary validation.
