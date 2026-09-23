# Frontend

`frontend/` is a React single-page app that covers every persona the API serves: a
storefront for customers, and staff screens for admins, warehouse managers and delivery
agents. It was built in Phase 21, against the running platform, and this document is
the why behind it.

- **Stack:** Vite 7, React 19, TypeScript 5.9 (strict, `exactOptionalPropertyTypes`,
  `noUncheckedIndexedAccess`), React Router 7, TanStack Query 5, zod 4. Five runtime
  dependencies; no UI kit, no state library, no HTTP client library.
- **Tests:** Vitest + React Testing Library + MSW, 347 tests, run against captured
  gateway responses rather than hand-written fixtures.
- **Decisions:** [ADR 011](adr/011-same-origin-react-spa.md) (one same-origin SPA),
  [ADR 012](adr/012-access-token-in-memory-only.md) (the token lives in memory only),
  [ADR 013](adr/013-boundary-validated-api-contract.md) (hand-written, validated API
  layer instead of codegen).

## Running it

| How | Command | URL |
|---|---|---|
| Whole platform in Docker | `docker compose up -d --build` | <http://localhost:8088> |
| Frontend dev server against a running platform | `cd frontend && npm ci && npm run dev` | <http://localhost:5173> |
| Kubernetes | `helm ... --set frontend.enabled=true` | the Ingress host |

Sign in as the bootstrap admin (`admin@smart-delivery.local` /
`local-dev-only-admin-password` locally) to create categories, products, warehouses and
stock; register to shop.

The gates, all of which CI's `frontend` job and the image build run:

```bash
npm run typecheck      # tsc, both projects
npm run lint           # eslint, zero warnings
npm test               # vitest (npm run test:coverage adds the 80% floors)
npm run build          # vite, per-page chunks
```

Two more need a running platform and are for a laptop, not CI:

```bash
npm run check:contract   # diff the six OpenAPI documents against src/api-snapshots/
npm run verify:live      # assert the behaviours the documents do NOT describe (writes test data)
```

## Production topology: one origin

The browser never talks to more than one origin. In development Vite proxies `/api`,
`/.well-known` and the Swagger paths to the gateway; in Docker Compose the `web` nginx
serves the bundle and proxies `/api` and `/.well-known` to `api-gateway:8080`; under
Kubernetes the Ingress routes `/api` to the gateway and `/` to the frontend pod. So the
app uses relative URLs only (`src/config.ts` refuses an absolute origin at startup),
the CSP can say `connect-src 'self'`, and there is no CORS configuration anywhere
because nothing is cross-origin. [ADR 011](adr/011-same-origin-react-spa.md) has the
alternatives.

`frontend/nginx/` holds the whole server policy:

- **Security headers**, on every response from one included file:
  `Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self';
  connect-src 'self'; img-src 'self' data:; ... frame-ancestors 'none'`, plus
  `nosniff`, `Referrer-Policy: no-referrer`, `X-Frame-Options: DENY` and a
  `Permissions-Policy`. No `'unsafe-inline'`: Vite emits no inline script, and React
  sets styles through the CSSOM, which `style-src` does not govern. `img-src` has no
  `https:` because the storefront renders no remote images (see below).
- **Caching:** `/assets/*` is content-hashed, so `max-age=31536000, immutable` -- but
  *without* `always`, so a 404 for a missing chunk is not cached for a year (the first
  version had `always`; a local run caught it). `index.html` is `no-store`, so a deploy
  is picked up on the next navigation.
- **History fallback:** `try_files $uri /index.html`, so `/orders/<id>` survives a
  reload. The smoke test asserts it.
- **Unprivileged:** listens on 8080, `USER 1000:1000`, pid and every temp path under
  `/tmp`, error log to the inherited `stderr` descriptor rather than an opened
  `/dev/stderr` -- the Helm chart's pod contract (read-only root filesystem, all
  capabilities dropped). The first version opened `/dev/stderr` and failed with `EACCES`
  when run as UID 1000; that is also why there is no access log (the gateway logs every
  API call with its correlation id; a log of static chunk fetches is volume, not insight).
- **The `/api` proxy is not in the image.** Compose mounts `gateway-proxy.conf` into
  `/etc/nginx/sdp/`; under Kubernetes the Ingress does that routing and the file is
  absent. `proxy_read_timeout` is 30s -- longer than the gateway's own 5s -- so a slow
  backend surfaces as the gateway's problem+json with a correlation id, not a bare nginx
  504. nginx does not set `X-Correlation-Id`: the browser sends one, and overwriting it
  would break the join between a user's support code and the logs.

## How the code is organised

```
src/
  lib/api/        the ONLY module that performs network I/O (http.ts), the error type,
                  zod schemas per backend service, query keys, the QueryClient
  lib/auth/       token store (no getter), session provider, guards, ?next= validation
  lib/cart/       cart reducer + persisted provider (the idempotency key lives here)
  domain/         pure rules: which statuses can cancel, polling cadence, key lifecycle
  components/     shared UI with no domain knowledge
  features/       one folder per area; each exports a RouteObject[] of lazy pages
  routes.tsx      the whole route tree and every guard, readable top to bottom
```

ESLint enforces the boundaries that matter: `fetch` is banned outside `src/lib/api/`, an
absolute `http://localhost:8...` literal is an error, `dangerouslySetInnerHTML` is an
error, request/body/header objects cannot be passed to `console`, and nothing outside
`features/admin/**` or `features/agent/**` may import from them except `routes.tsx` --
which is what keeps staff code out of the customer's bundle. Every page is its own chunk,
loaded by the router when it is navigated to.

**Server state lives in TanStack Query and nowhere else.** There are two React contexts,
session and cart, because that is all the client state there is.

## Signing in, and why a reload signs you out

The platform issues one-hour access tokens with no refresh endpoint and no revocation
([security.md](security.md)). The token is kept in a module-scoped variable with no
getter -- not `localStorage`, not `sessionStorage`, not React state -- so it cannot
outlive the tab and no component can read it. The cost is that a reload signs the user
out; [ADR 012](adr/012-access-token-in-memory-only.md) weighs that and names the
condition that would reverse it. What softens it:

- The **cart survives** (it is in `localStorage` and holds no secret), including its
  idempotency key, so a reload mid-checkout followed by signing in retries safely.
- A non-secret **session hint** in `sessionStorage` lets the login page say "reloading
  signed you out" instead of looking like a bug.
- The token is **dropped 30 seconds before expiry** (the same skew order-service uses
  for its own service tokens), and a banner warns two minutes ahead. A POST is never sent
  with a token that is about to be rejected.

Any 401 ends the session once, however many requests hit it at the same time. **A 403
never signs anyone out**: FORBIDDEN means the token is fine and this one thing is not
allowed, which with ownership checks on orders, addresses and deliveries is routine.

**Route guards are user experience, not security.** Every rule is enforced by the
services; the guards only avoid showing someone a screen whose every request would 403.
Role sets are taken from the server's actual rules, not a persona list -- warehouses and
stock accept `ADMIN` **or** `WAREHOUSE_MANAGER`, because inventory-service does.

## Placing an order: the Idempotency-Key

`POST /api/v1/orders` carries an `Idempotency-Key`; order-service answers a replay with
`201` and the *original* order. The key is only as good as its lifecycle, and the rule is
**the key must be older than the click**:

- minted when checkout is entered with a non-empty cart, persisted inside the cart, and
  Place order is disabled until it exists -- so no click can happen without one;
- reused by a double-click, the automatic retry (network error or 502/503/504, at most
  twice), a manual retry, a page refresh, and the re-login a mid-checkout expiry forces;
- rotated only by a `201`, by any edit to the lines or the address (the old key now
  describes a different body, and reusing it would be a 409), or by an explicit Start
  over;
- never silently re-minted and re-POSTed after a 409 -- that is the one path to a real
  duplicate. The user is shown "nothing was ordered twice" and a support code.

The rules are pure functions in `src/domain/idempotency.ts`, table-tested, and
`features/checkout/__tests__/idempotencyKey.test.tsx` records every key the server sees
across each scenario above and asserts there is one.

**What the live walk-through found here.** A real browser double-click sent two
concurrent POSTs: both clicks land before React re-renders the button as disabled
(user-event, in the test suite, yields between clicks, so the tests had not seen it).
That exposed a server bug: two concurrent same-key requests made order-service return
**500** instead of replaying, because its recovery re-read ran inside a transaction
PostgreSQL had already aborted. Both are fixed -- a synchronous in-flight guard on the
three non-idempotent buttons (place order, cancel, complete delivery), and the
order-service transaction boundary ([order-flow.md](order-flow.md#idempotency)) -- and
both have tests that fail on the old code.

**Prices and stock are checked before checkout, not trusted.** The cart stores the price
when an item was added; the cart and checkout pages re-read each product and its stock.
A changed price blocks checkout until the person accepts it (which edits the lines, which
rotates the key -- correctly), and a line asking for more than `totalAvailable` blocks it
with "Only N left". Stock is advisory -- the saga's reservation is the real check -- but
showing it prevents the commonest `FAILED` order: one placed against stock that was
already zero, which fails four seconds later for a reason the customer cannot see.

## Following an order

`GET /api/v1/orders/{id}/status` is polled on a schedule that lives in
`src/domain/polling.ts`: every second for the first 10s of an order's life, 2s until 30s,
5s until two minutes, then 15s; 3s at `PAID`; and never at `SHIPMENT_CREATED`,
`OUT_FOR_DELIVERY` or a terminal status, because those wait on a person (an admin
assigning a courier, an agent completing a delivery), not on the saga. Polling stops at
16 minutes -- past order-service's stuck-saga reaper threshold -- with a card saying the
order has not failed and the recovery process has it. It pauses in a hidden tab. The
orders list polls at most five rows individually and refreshes the list every 30s beyond
that.

Ten saga statuses are shown as six milestones (Placed, Reserving stock, Taking payment,
Preparing shipment, On its way, Delivered), and **a `*_PENDING` status never renders its
own milestone as done**. `FAILED` gets exact, deliberately limited copy: the order was
cancelled and stock released, and *if* you were charged, quote this reference. It does
not say "you have not been charged" or "you were refunded", because a customer token
cannot read payments and `OrderResponse` carries no failure reason -- the screen does not
know. The admin order lookup is where payment state is visible.

**Cancelling** re-reads the status when the dialog opens (what is on screen can be a poll
old) and explains instead of offering the button if the order has moved on. A 409 on
cancel is a lost race, not an error: the page refetches and says what happened ("This
order shipped just before your cancellation went through"), never the server's `detail`.

## Errors

Every failure the app can see -- a validated 400, a 403, a 409 from the saga, an nginx
502, the gateway's 504, a rejected fetch, a response that no longer matches its schema --
becomes one type, `ApiProblem`, with a stable `code`. Screens branch on `code`, never on
`detail` ([security.md](security.md#error-codes) reserves the right to reword it).
`SERVICE_UNAVAILABLE` is shown in amber as transient ("your data is safe"), because on a
checkout screen the difference between waiting and placing a second order is the wording.
Every error shows a **support code** -- the request's correlation id, which the client
generates and the gateway forwards -- and `/support` lists the last twenty requests with
their codes and a Copy report button that copies method, path *template*, status and
code only: no bodies, no headers, no ids.

## Staff screens, and what the API does not allow

Built to what the API actually supports, with the gaps stated on screen rather than
designed around:

- **Products and categories** (ADMIN). The product form always sends every field,
  because `PUT` is a full replace and omitting `description` would erase it. Unticking
  "Sold in the shop" is the safe way to stop selling something.
- **Warehouses and stock** (ADMIN or WAREHOUSE_MANAGER). `POST /api/v1/inventory` is
  create-only and there is no adjust or restock endpoint, so the form creates a
  product's first stock row in a warehouse and says that quantities cannot be changed
  afterwards; a duplicate gets that sentence, not a generic 409.
- **Shipments and agents** (ADMIN). Assigning an agent is what sends an order out, and
  the dialog says so. Creating an agent takes a pasted user id: there is no user search.
- **Order lookup** (ADMIN). A lookup by order id or customer id, with payment and
  shipment state -- deliberately **not** an order list. There is no all-orders endpoint,
  and building one from shipments would silently omit every failed or stuck order, which
  are the ones an admin needs. The page says so and names the endpoint that would fix it.
- **Deliveries** (DELIVERY_AGENT or ADMIN). A worklist and a Mark as delivered button,
  and a line saying addresses are not available: `DeliveryResponse` has no order or
  address, and agents cannot read shipments. No endpoint can grant `DELIVERY_AGENT`, so
  this surface could not be exercised end to end.

## Deliberately not built

- **Manual charge, manual refund, and manual reserve/release/deduct.** The endpoints are
  reachable to an admin or warehouse manager and are still wrong to expose: the saga owns
  them, a manual charge double-charges, and a manual refund races the cancellation
  compensation (ADR 008).
- **Remote product images.** `imageUrl` is an arbitrary URL an admin typed, with no
  upload endpoint or validation; rendering it would widen the CSP and leak page URLs to
  whatever host was pasted. Products get a deterministic lettered tile from the SKU.
- **Server-side rendering.** Everything worth rendering is behind a memory-held token,
  and SSR would force the cookie-based session this platform has no endpoint for.
- **Optimistic updates.** Every write waits for the server; invalidation is fast and
  honest, and the saga is exactly the place optimism lies.

## What has been verified, and how

- **Unit and integration tests:** 347 Vitest tests, lint and typecheck clean, 98% line
  coverage on `lib/api`, `domain` and `lib/cart` (floor 80%).
- **Live browser walk-through** (Playwright + Chromium) of the built bundle through the
  real nginx config in front of all eight services: admin creates a category, product,
  warehouse and stock (and gets the create-only copy on a duplicate); a signed-out
  visitor adds to cart; registers; adds an address; reloads mid-checkout (signed out, cart
  and key kept); signs in; double-clicks Place order; follows the order to "waiting for a
  courier"; a customer on an admin URL sees Not allowed; an unknown URL gets the in-app
  404. No CSP violation or page error was logged.
- **`npm run verify:live`** (15 checks) and **`npm run check:contract`** (six documents
  match) against the same stack.
- **nginx run as UID 1000** with the production config: shell, deep link and `/api` proxy
  all 200, CSP present, every write under `/tmp`.
- **The smoke test's new web-tier block** run against that nginx.

**Not verified here:** building the Docker image (no Docker daemon in this environment --
CI's `docker-build` job builds and Trivy-scans it on every push) and running it in a
cluster (there is none; the Helm chart renders and validates, see
[kubernetes.md](kubernetes.md)). The nginx under test was the distribution's 1.24; the
image uses 1.27.
