# ADR 012: The access token lives in memory only

## Status
Accepted (Phase 21)

## Context

`POST /api/v1/auth/login` returns a one-hour RS256 access token. There is no refresh
token and no revocation ([security.md](../security.md)): a token that leaks is valid for
the rest of its hour and nothing can kill it.

A browser app has four places to keep it: `localStorage`, `sessionStorage`, a cookie, or
memory. A cookie would need the platform to issue one -- it does not -- and would bring
CSRF back for every mutating endpoint. The other three differ in how long a stolen token
outlives the page that held it.

## Decision

**The token is held in a module-scoped variable in `src/lib/auth/tokenStore.ts`, with no
getter.** The HTTP layer is given a function that attaches it to outgoing requests; no
component, hook or context can read it. It is dropped 30 seconds before expiry (the skew
order-service already uses for its own service tokens), and any 401 ends the session.

A **non-secret session hint** (user id, roles, expiry) goes in `sessionStorage` so that
after a reload the login page can explain what happened. The **cart and its
idempotency key** go in `localStorage`; they are not credentials.

## Consequences

- **A reload signs the user out.** That is the real cost, paid on every reload, by every
  user. Mitigations: the cart and a mid-checkout idempotency key survive, so re-login and
  retry is safe; the login page says why; a banner warns two minutes before expiry.
- What this protects against is precise: a token exfiltrated by XSS stops being usable
  when the tab closes, instead of persisting in storage for the rest of its hour. It does
  **not** stop XSS from using the token while the tab is open -- nothing on the client
  can -- which is why the CSP and the "no `dangerouslySetInnerHTML`" lint rule are the
  primary defence and this is the second.
- The argument against, stated fairly: against an attacker who already runs script in
  the page, `sessionStorage` adds little exposure over memory (both die with the tab),
  while the UX cost of memory-only is certain and constant. The deciding fact is the
  platform's lack of revocation: a leaked token cannot be cut off, so the only lever left
  is how long it lives on the client, and memory is the shortest.

## Reversal

Two conditions, either of which moves the token to `sessionStorage` -- a change confined
to `tokenStore.ts`, which is why it is hidden behind that module:

1. Evidence that reload-sign-outs cost checkouts (abandonment at the login page after a
   reload), or
2. refresh tokens are not scheduled by the next planning cycle.

The proper fix is a refresh endpoint issuing an httpOnly, `SameSite=Strict` cookie scoped
to `/api/v1/auth/refresh`. Note that this reintroduces CSRF for that one route, so the
platform's blanket `csrf().disable()` would have to be revisited for `/api/v1/auth/**`;
the access token itself would stay in memory either way.
