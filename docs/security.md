# Security

> Implemented starting in Phase 2 (User Service issues tokens) and enforced by every
> other service as each is built. This document fixes the design up front.

## Authentication

`user-service` is the only service that verifies a password. On successful
login it issues a JWT signed with an HMAC secret shared only between backend services
(never exposed to clients), containing:

```json
{
  "sub": "<userId>",
  "roles": ["CUSTOMER"],
  "iat": 1731000000,
  "exp": 1731003600
}
```

Passwords are hashed with BCrypt (`spring-security-crypto`'s `BCryptPasswordEncoder`);
the plaintext password is never logged, and the hash is never returned in any API
response.

Every other service is a Spring Security OAuth2 **resource server**, validating the
JWT's signature and expiry on every request — it does not call back to `user-service`
to check the token. This is why the signing secret must be shared configuration across
services rather than a per-service secret: any service must be able to independently
verify a token `user-service` issued.

> **Known simplification, to revisit before any real deployment:** HMAC (HS256) with a
> shared secret is simpler to stand up locally than RS256 with a JWKS endpoint, but it
> means every resource server that can *verify* a token also holds the key that could
> *sign* one. A production rollout should move `user-service` to RS256 and publish a
> JWKS endpoint so verification keys are public and only `user-service` holds the
> private signing key. Recorded here rather than silently decided because it's a real
> trade-off, not a formality.

## Authorization

Role-based, enforced per-endpoint with Spring Security method security
(`@PreAuthorize`), not scattered `if` checks in controllers:

| Role | Can do |
|---|---|
| `CUSTOMER` | Create/view/cancel **their own** orders, manage their own profile/addresses |
| `ADMIN` | Manage users, products, categories; view all orders and inventory |
| `WAREHOUSE_MANAGER` | Manage inventory: reserve/release/deduct/adjust stock |
| `DELIVERY_AGENT` | View and update **their assigned** shipments |

The recurring rule, called out because it's the one most likely to be gotten wrong
under time pressure: **never trust a `userId` supplied in the request body or path when
the authenticated identity is available from the JWT.** `GET /api/v1/orders/user/{userId}`
as a customer only ever returns data for `{userId} == authentication.getName()`; a
customer requesting someone else's `userId` gets `403 Forbidden`, not their data. Admins
are the one role explicitly allowed to pass an arbitrary `userId`, and that's enforced
by role check, not by trusting the path variable.

## Transport and secrets

- No password, JWT, or card-like payment data is ever written to application logs
  (enforced by not passing those fields to any `log.*` call, and by scrubbing
  request/response logging filters — see [observability.md](observability.md)).
- Local-dev secrets (`docker-compose.yml` DB credentials, the JWT signing secret) are
  fixed, clearly-labeled non-secrets scoped to the local Compose network — never reused
  as a real credential and never intended to be.

## HTTP status codes

Consistent mapping, enforced by a global exception handler per service (each service
defines one; it is a few dozen lines, deliberately not shared as a library — see
[architecture.md](architecture.md#why-microservices-and-why-these-boundaries)):

| Situation | Status |
|---|---|
| Validation failure | `400 Bad Request` |
| No/invalid JWT | `401 Unauthorized` |
| Valid JWT, insufficient role/ownership | `403 Forbidden` |
| Resource doesn't exist | `404 Not Found` |
| State conflict (e.g. duplicate idempotency key with different body, optimistic lock loss) | `409 Conflict` |
| Unhandled server error | `500 Internal Server Error`, **no stack trace in the body** |

Every error response follows the shape defined in the master engineering brief section
19:

```json
{
  "timestamp": "2026-08-11T12:00:00Z",
  "status": 400,
  "error": "VALIDATION_ERROR",
  "message": "Request validation failed",
  "path": "/api/v1/orders",
  "correlationId": "5b1a7e2e-9c3d-4a2b-8f1e-2d6c9a0b1234"
}
```
