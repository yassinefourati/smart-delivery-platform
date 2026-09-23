# ADR 011: One same-origin React SPA for every persona

## Status
Accepted (Phase 21)

## Context

The platform had an API and no client. Four kinds of people use it -- customers, admins,
warehouse managers, delivery agents -- and a user's `roles` is an array: the bootstrap
admin is also someone who can shop, and `ADMIN` bypasses the ownership checks on orders
and deliveries. Every endpoint worth calling except the catalog, public stock and
registration requires a bearer token, and the only way to get one is
`POST /api/v1/auth/login` (no refresh endpoint, no cookies anywhere in the auth design;
see [security.md](../security.md)).

The gateway has no CORS configuration. A browser page served from any origin other than
the gateway's cannot call it.

## Decision

**One single-page app, served from the same origin as the API, with no server-side
rendering.**

- *Same origin.* The browser only ever talks to one origin: Vite's proxy in development,
  the `web` nginx in Docker Compose, the Ingress under Kubernetes. The app builds relative
  URLs only; `src/config.ts` accepts a path prefix for mounting under a sub-path and
  refuses anything with a scheme or host at startup.
- *One bundle, split per page.* Every persona's screens are in one app; each page is a
  lazy chunk, and an ESLint rule stops customer code from importing staff code, so a
  customer does not download the admin screens. Role-based guards decide which screens are
  offered -- as user experience only; the services enforce every rule.
- *No SSR.* There is nothing public worth rendering on a server except the catalog, which
  has no SEO requirement here, and SSR would need the server to hold the user's session --
  a cookie design this platform does not have and ADR 012 does not want.

## Consequences

- No CORS configuration exists or is needed, and the CSP can be `connect-src 'self'` with
  no `https:` exceptions.
- The same artifact is correct in every environment: there is no runtime config file to
  mount and no origin baked into the bundle.
- The deployment must provide the single origin. Compose does it with a mounted nginx
  proxy file; the Helm chart does it with the Ingress (`/api` to the gateway, `/` to the
  frontend). A frontend on a CDN at another origin is not supported.
- One app means one release cadence for customer and staff screens.

## Alternatives considered

- **Separate admin and storefront apps.** Would mean two logins for the same person
  (an admin who shops), duplicated API and auth layers, and no real isolation gain: the
  services enforce the roles, not the bundle boundary. Code-splitting gives the bundle-size
  benefit without the duplication.
- **Cross-origin SPA (CDN) with CORS on the gateway.** Workable, but it is a gateway code
  change plus an allowlist that must include the `Idempotency-Key` request header (omit it
  and checkout specifically breaks) and expose `X-Correlation-Id` (omit it and support codes
  vanish on exactly the gateway errors that have no body). Worth doing only if the frontend
  must live on another origin.
- **Next.js / SSR.** See above: it would drag in a server-held session this platform has
  no endpoint for.

## Reversal

Moving to a separate origin is the gateway CORS change above plus changing
`VITE_API_BASE_PATH`'s validation; no screen changes.
