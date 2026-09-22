# ADR 007: Asymmetric JWT signing and JWKS

## Status
Accepted (supersedes the HS256 arrangement described in [ADR 002](002-kafka-for-events.md)'s
sibling docs and flagged as a known simplification in [security.md](../security.md) since
Phase 2)

## Context
From Phase 2 until Phase 16, every service in the platform verified JWTs with the same
HMAC secret (`jwt.secret`, HS256). That is the simplest thing that works, and it was
documented from the start as a simplification to revisit. It is also a genuine
vulnerability, not a stylistic one, and it is worth being precise about why.

**HMAC has one key, used for both operations.** Verifying a signature and producing one
are the same capability. So every service that could check an ADMIN token could also
*mint* one. A single compromised service — the least important one, the one with the
smallest attack surface, the one nobody reviews carefully — yields the ability to
impersonate any user, any role, anywhere in the platform. There is no blast radius: the
blast radius is everything.

order-service made that concrete rather than theoretical. `InternalServiceTokenProvider`
signed its own `SERVICE`-role tokens with the shared secret and presented them to
inventory-service and payment-service. This worked precisely *because* of the weakness:
the tokens were real, and inventory-service accepted them, because inventory-service held
the same key and could have signed identical ones itself.

Secondary problems the same design carried:

- **Rotation was a flag day.** Changing the secret invalidates every outstanding token
  and requires every service to be reconfigured simultaneously.
- **No issuer or audience validation.** A token was accepted on its signature and expiry
  alone, so a token minted by this platform's secret for one purpose was valid for every
  purpose, and any other system sharing that secret could mint one too.
- **No service identity.** `SERVICE` was a role, not an identity: nothing distinguished
  order-service's token from one any other holder of the secret had produced.

## Decision

**user-service becomes the sole issuer, signing with RS256.**

- The private key comes from configuration (`jwt.signing-key.private-key`, a PKCS#8 PEM
  from an env var or a mounted file). No other service has it, and there is no
  configuration key by which another service *could* have it.
- Every token carries a `kid` header naming the key that signed it, plus `iss`, `aud`,
  `sub`, `roles`, and `exp`.
- For local development only, `JwtKeyProvider` generates a throwaway RSA key pair when no
  private key is configured, and logs a loud warning saying exactly what is wrong with
  that (tokens die with the process; a second replica would sign with a different key).
  This is why no production key exists anywhere in this repository to be committed by
  accident.

**`GET /.well-known/jwks.json` publishes the public keys**, unauthenticated, and routed
through the gateway. Public is not a concession: a public key is not a credential, and a
resource server must be able to fetch the key set *before* it can authenticate anything,
which makes requiring a token circular.

**Key rotation is a configuration change, not an outage.** The JWKS publishes the active
key and any number of retired ones (`jwt.retired-keys`); tokens name their key by `kid`.
Rotating is: add the new key as `signing-key`, move the old one to `retired-keys`, and
drop it once nothing signed by it can still be unexpired.

**Every other service becomes a standard Spring Security OAuth2 resource server.**
`spring-boot-starter-oauth2-resource-server` plus three properties —
`jwk-set-uri`, `issuer-uri`, `audiences` — replace the hand-written `JwtService` and
`JwtAuthenticationFilter` in all five. A `JwtAuthenticationConverter` maps the existing
`roles` claim to `ROLE_*` authorities and sets the principal name to `sub`, so every
`@PreAuthorize` ownership check keeps working *unchanged* — that is the point: this
phase changes how tokens are trusted, not who can do what. The existing entry point and
access-denied handlers are wired into the resource server so the JSON body of a 401 or
403 is byte-for-byte what it was.

`issuer-uri` is set alongside `jwk-set-uri` deliberately. Spring Boot uses the key set
URI for keys and the issuer only for claim validation, so nothing here performs OIDC
discovery against a `/.well-known/openid-configuration` endpoint that does not exist.

**Service-to-service authentication becomes a real client-credentials grant.**
`POST /api/v1/auth/service-token` on user-service takes a client id and secret and
returns a short-lived (5m) `SERVICE`-role RS256 token. Each calling service has its own
credential from its own env var. order-service caches the token and refreshes it ahead of
expiry (`ServiceTokenProvider`).

user-service verifies its own tokens through a `JwtDecoder` built from its in-memory
public keys rather than by fetching its own JWKS over HTTP — same signature, issuer,
audience, and expiry checks as everyone else, without making its ability to authenticate
anyone depend on it being reachable from itself.

## Consequences

- **A compromised service can no longer forge identities.** It holds a public key. It can
  check a token and cannot produce one — not for a user, not for an admin, not for another
  service. This is the whole point, and it is the one property that was impossible before.
- **Compromising order-service's client secret yields order-service's own privileges, and
  only those.** It cannot mint an ADMIN token, and it cannot impersonate another service.
- **user-service is now on the critical path for service-to-service calls.** It was
  already on the critical path for logins, and the cost is bounded: tokens are cached for
  minutes, and a refresh that fails while the current token is still valid reuses it. A
  fetch that genuinely cannot succeed raises `ServiceTokenUnavailableException`, which the
  saga's existing Kafka retry and dead-letter handling already covers.
- **That exception is ignored by the `inventory-service` and `payment-service` circuit
  breakers**, because it is raised inside them (the token is fetched on the way into the
  call) and says nothing about those services' health — exactly the reasoning that already
  exempts `InsufficientStockException` (see [resilience.md](../resilience.md)). This is
  the only change this phase makes to Resilience4j; the downstream clients' breaker,
  retry, bulkhead, and rate limiter settings are otherwise untouched.
- **Verification now depends on a network fetch.** A resource server that cannot reach the
  JWKS cannot authenticate. Spring Security caches the key set and refetches on an unknown
  `kid`, and the endpoint sets a short `Cache-Control`, so this is a startup-and-rotation
  concern rather than a per-request one.
- **jjwt is gone from every service.** user-service signs with Nimbus JOSE+JWT (already on
  the classpath as part of Spring Security's JOSE support); everything else verifies
  through Spring Security and touches no JWT library directly.
- **RS256 signing is slower than HMAC.** Irrelevant here: it happens once per login and
  once per service-token refresh, not per request. Verification, which *is* per request,
  is an RSA public-key operation — more expensive than an HMAC check and far cheaper than
  the database call it precedes.

## Alternatives considered

- **Keycloak, or another off-the-shelf identity provider.** Almost certainly the right
  answer for a real deployment, and it would bring refresh tokens, revocation, consent,
  and an admin UI for free. Rejected *for this repository* because the point of
  user-service is to show the mechanism, and because adopting an IdP would replace the
  problem with a configuration exercise rather than solve it visibly. Recorded as the
  primary follow-up in [security.md](../security.md).
- **ES256 instead of RS256.** Smaller keys and signatures, equally well supported. RS256
  chosen only because it is the more widely-interoperable default; nothing in the design
  depends on the choice, and `JwtKeyProvider` would change in one place.
- **Keeping HMAC but giving each service a distinct verification secret.** Does not work:
  the point of a shared secret is that any service can verify a token the issuer signed,
  and per-service secrets would mean per-service tokens.
- **Standard RFC 6749 form-encoded client credentials.** The service-token endpoint takes
  JSON like every other endpoint in this platform, because nothing here is an OAuth2
  client library expecting the standard form. Worth changing the day something is.
