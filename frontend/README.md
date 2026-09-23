# Smart Delivery -- web app

The React SPA for customers and staff. The design, and why, is in
[docs/frontend.md](../docs/frontend.md); this file is how to work on it.

```bash
npm ci
npm run dev             # http://localhost:5173, proxying /api to the gateway on :8080
```

The platform must be running for the dev server to be useful: `docker compose up -d
--build` from the repository root (the full app is then also at http://localhost:8088),
or the services as JVM processes ([docs/local-development.md](../docs/local-development.md)).

| Script | What it does |
|---|---|
| `npm run typecheck` | `tsc` over the app and the config files |
| `npm run lint` | ESLint, zero warnings allowed |
| `npm test` / `npm run test:coverage` | Vitest (+ coverage floors on `lib/api`, `domain`, `lib/cart`) |
| `npm run build` | Production bundle in `dist/` |
| `npm run check:contract` | Diff the gateway's six OpenAPI documents against `src/api-snapshots/` (`--update` to accept) |
| `npm run verify:live` | Assert live behaviours the documents omit; **writes** a user, an address and orders |

`Dockerfile` builds the bundle (running the four gates first) and serves it with nginx
on port 8080 as UID 1000; `nginx/` holds the server config. CI builds, scans and
publishes the image; the smoke test checks the running web tier.
