# Security

> Implemented starting in Phase 2 (User Service issues tokens) and enforced by every
> other service as each is built. This document fixes the design up front.

## Authentication

`user-service` is the only service that verifies a password, and — as of Phase 16
([ADR 007](adr/007-asymmetric-jwt-signing.md)) — the only service that can sign a token.
On successful login it issues a JWT signed with **RS256**:

```json
{
  "kid": "sdp-prod-key-2",          // header: which key signed this
  "alg": "RS256"
}
{
  "sub": "<userId>",
  "iss": "https://user-service.smart-delivery.local",
  "aud": "smart-delivery-platform",
  "roles": ["CUSTOMER"],
  "email": "customer@example.com",
  "jti": "<unique id>",
  "iat": 1731000000,
  "exp": 1731003600
}
```

Passwords are hashed with BCrypt (`spring-security-crypto`'s `BCryptPasswordEncoder`);
the plaintext password is never logged, and the hash is never returned in any API
response.

### Why asymmetric, and what it fixed

Until Phase 16 every service verified tokens with the same HMAC secret. With HMAC,
verifying a signature and producing one are the *same capability* — so every service that
could check an ADMIN token could also mint one. One compromised service, however
unimportant, meant the ability to impersonate anyone anywhere in the platform.

With RS256 that is structurally impossible. `user-service` holds the private key; every
other service holds only the public half, fetched from the JWKS. A compromised
inventory-service can verify a token and cannot produce one — not for a user, not for an
admin, not for another service.

This is asserted, not assumed: every service's integration tests present a token signed
with the old shared secret, claiming ADMIN, and require a `401`
(`aTokenSignedWithTheOldSharedHmacSecretIsRejected`), alongside `alg: none`, expired,
wrong-issuer, wrong-audience, tampered, and unknown-`kid` tokens.

### Key distribution: JWKS

`GET /.well-known/jwks.json` on user-service (routed through the gateway too) publishes
the public keys as a JSON Web Key Set. It is **public and unauthenticated by design** —
a public key is not a credential, and a resource server has to fetch the key set before
it can authenticate anything, which makes requiring a token circular. A test asserts the
response carries the public modulus and exponent (`n`, `e`) and none of the private
parameters (`d`, `p`, `q`).

Every other service is a standard Spring Security OAuth2 **resource server**, configured
with three properties and no hand-written JWT code at all:

```yaml
spring.security.oauth2.resourceserver.jwt:
  jwk-set-uri: http://user-service:8081/.well-known/jwks.json
  issuer-uri:  https://user-service.smart-delivery.local   # validated, not discovered
  audiences:   smart-delivery-platform
```

Signature, expiry, issuer, and audience are all checked on every request; no service
calls back to user-service to validate a token. `issuer-uri` is set *alongside*
`jwk-set-uri` on purpose: Spring Boot then uses the key set URI for keys and the issuer
only for claim validation, so nothing performs OIDC discovery against an endpoint this
platform does not have.

A `JwtAuthenticationConverter` per service maps the `roles` claim to `ROLE_*` authorities
and sets the principal name to `sub`, which is what keeps every `@PreAuthorize` ownership
check below working unchanged.

### Key rotation

Tokens carry a `kid`; the JWKS can publish more than one key. Rotating is a configuration
change, not a flag day:

1. Add the new key as `jwt.signing-key` (new `kid`, new PEM).
2. Move the previous key's *public* half to `jwt.retired-keys`.
3. Once nothing signed by it can still be unexpired (one token lifetime), drop it.

Tokens signed by the retired key keep verifying throughout, because the JWKS still
publishes it and the `kid` still resolves. Spring Security refetches the key set when it
sees an unknown `kid`, so a new key is picked up without a restart.

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

## DELIVERY_AGENT ownership (Phase 9)

`delivery-service`'s `DeliveryAgent.userId` links a delivery-agent record to the
user-service `User` (with the `DELIVERY_AGENT` role) whose JWT authenticates them --
delivery-service trusts this link rather than calling user-service to verify it, the
same trust boundary order-service already extends to the `userId` it reads out of a
JWT. `DeliveryController`'s ownership checks (`getById`, `complete`,
`GET /agent/{userId}`) compare that JWT subject against the delivery's assigned agent,
exactly the "role gets you past the door, ownership decides what you can touch" pattern
`/api/v1/orders/user/{userId}` already uses: a `DELIVERY_AGENT` completing someone
else's delivery gets `403 Forbidden`, not someone else's delivery. `ADMIN` bypasses
ownership entirely, as everywhere else in this platform. Agent management
(`/api/v1/agents/**`) and shipment dispatch (`/api/v1/shipments/**`) are ADMIN-only --
back-office concerns an agent has no reason to reach.

## Service-to-service authentication (client credentials)

The order Saga needs order-service to call inventory-service (reserve/release/deduct) and
payment-service (charge/refund) — endpoints that must not be open to arbitrary customer
JWTs. Until Phase 16, `InternalServiceTokenProvider` in order-service *minted its own*
`SERVICE` tokens with the shared HMAC secret. That worked precisely because of the
weakness described above: the tokens were real, and inventory-service accepted them,
because inventory-service held the same key and could have signed identical ones.

It is now a real client-credentials grant ([ADR 007](adr/007-asymmetric-jwt-signing.md)):

- `POST /api/v1/auth/service-token` on user-service takes a client id and secret and
  returns a short-lived (5 minutes) `SERVICE`-role RS256 token. It is reachable without a
  bearer token because it is where a caller goes to *get* one; it is authenticated by the
  credential in the body instead.
- Each calling service has **its own** credential, from its own env var
  (`ORDER_SERVICE_CLIENT_SECRET`). A leaked credential yields that service's privileges
  and nothing else: it cannot mint an ADMIN token and cannot impersonate another service.
- Secrets are compared in constant time (`MessageDigest.isEqual`). An unknown client id
  and a wrong secret produce the same error with the same message, so a caller cannot
  enumerate which client ids exist.
- `ServiceTokenProvider` in order-service caches the token and refreshes it 30s ahead of
  expiry. A refresh that fails while the current token is still valid reuses it, so a
  brief user-service blip does not fail orders holding a perfectly good credential.

inventory-service and payment-service accept `SERVICE` on exactly the endpoints the saga
calls and nowhere else — `SERVICE` cannot create a warehouse. Neither of them can produce
such a token any more.

## Transport and secrets

- No password, JWT, or card-like payment data is ever written to application logs
  (enforced by not passing those fields to any `log.*` call, and by scrubbing
  request/response logging filters — see [observability.md](observability.md)).
- Local-dev secrets (`docker-compose.yml` DB credentials, `ORDER_SERVICE_CLIENT_SECRET`)
  are fixed, clearly-labeled non-secrets scoped to the local Compose network — never
  reused as a real credential and never intended to be.
- **There is no signing key in this repository at all.** `docker-compose.yml` sets no
  `JWT_PRIVATE_KEY`, and user-service generates a throwaway RSA key pair at startup when
  none is configured, logging a warning that says exactly what is wrong with that (tokens
  die with the process; a second replica would sign with a different key). A real
  deployment mounts a PKCS#8 PEM or injects it from a secret manager. This is deliberate:
  the safest production key is one that never existed in version control to begin with.

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

## Deliberately out of scope (follow-ups)

Recorded here rather than silently omitted, in the same spirit as the HS256 trade-off this
document used to carry:

- **Refresh tokens.** A login returns one access token with a one-hour lifetime and no way
  to renew it without re-authenticating. A refresh-token flow (rotating, single-use,
  stored server-side so it can be invalidated) is the normal answer and is a self-contained
  addition to user-service.
- **Token revocation.** Nothing can invalidate an issued access token before it expires —
  the unavoidable cost of stateless verification, and the reason the lifetime is an hour
  rather than a day. Logout, a password change, or a role change all take up to one token
  lifetime to take effect. Options, in increasing cost: shorten the lifetime; a denylist of
  `jti` values that every resource server checks (which reintroduces a shared lookup); or
  full introspection (which gives up stateless verification entirely).
- **Replacing user-service's auth with Keycloak** (or another off-the-shelf identity
  provider). Almost certainly the right answer for a real deployment: it brings refresh
  tokens, revocation, account recovery, MFA, consent, and an admin UI, none of which are
  interesting to hand-build. The migration is smaller than it looks, because every service
  but user-service is already a *standard* resource server pointed at a JWKS — switching
  issuers is changing three properties. What would have to move is user-service's own
  `/login`, its client-credentials endpoint, and the `roles` claim mapping. See
  [ADR 007](adr/007-asymmetric-jwt-signing.md) for why it was not done now.
- **Rate limiting and lockout on the auth endpoints.** `/api/v1/auth/login` and
  `/api/v1/auth/service-token` accept unlimited attempts. Neither leaks which half of a
  credential was wrong, but neither slows an attacker down either.
- **Storing service-client secrets hashed.** They are deployment configuration rather than
  user-chosen passwords, so the offline-cracking risk BCrypt addresses applies differently
  — but a secret manager, or a hash plus a rotation procedure, would still be better than
  an env var.
