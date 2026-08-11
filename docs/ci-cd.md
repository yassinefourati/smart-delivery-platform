# CI/CD

`.github/workflows/ci.yml` runs on every push to `main` and every pull request into it.

## Jobs

- **`build-and-test`** — `mvn -B clean verify` across the whole 8-module reactor: every
  unit test and every Testcontainers integration test (see
  [docs/testing.md](testing.md)) runs here, since GitHub Actions runners have Docker
  available (unlike the sandbox this platform was built in). Surefire reports are
  uploaded as a build artifact on every run, pass or fail, so a failure's actual test
  output is one click away instead of buried in the raw log.
- **`docker-build`** — builds each of the 8 services' Docker images from their
  `Dockerfile`s, matrix'd so one service's build failing doesn't block the others from
  reporting. Every push and PR gets a real build (proving the `Dockerfile` still
  produces a working image), cached via `type=gha` so a small code change doesn't
  re-resolve every Maven dependency from scratch. **Only a push to `main`** additionally
  logs in to GitHub Container Registry and publishes the image, tagged `:latest` and
  `:<commit-sha>` — a PR (including one from a fork, which wouldn't have registry
  credentials anyway) only ever proves the image builds, never publishes one.
- **`compose-config`** — `docker compose config --quiet`, validating `docker-compose.yml`
  parses and every variable substitution resolves, on every push and PR.

## Published images

`ghcr.io/yassinefourati/smart-delivery-platform/<service>:latest` (and
`:<commit-sha>` for a specific build) for each of the 8 services, published on every
push to `main`. No extra registry secret is needed — GHCR accepts the workflow's own
automatic `GITHUB_TOKEN`, scoped to `packages: write` at the job level only (every other
job, and every step in this job before the publish, only needs `contents: read`).

## Dependency updates

`.github/dependabot.yml` — weekly PRs for the Maven reactor (one entry at the repo root
covers all 8 modules' `dependencyManagement`), the 8 services' Dockerfile base images
(one entry per Dockerfile, since Dependabot's Docker ecosystem doesn't follow images
transitively across files), and the GitHub Actions used in `ci.yml` itself.

## What's deliberately not here

**Deployment.** This pipeline builds, tests, and publishes images — it does not deploy
them anywhere. There is no Kubernetes manifest, Helm chart, or cloud environment
anywhere in this repository to deploy *to*; adding a "deploy" job would mean inventing
infrastructure this project doesn't have and pointing at a target that doesn't exist,
which is worse than not having the step. `docker-compose.yml` remains the actual
"run this platform" mechanism, for local development only (see
[docs/local-development.md](local-development.md)) — publishing images to GHCR is
the natural stopping point for a CD pipeline with no real deployment target, and the
foundation a real one would build on.

**Static analysis** (Checkstyle/SpotBugs/PMD) was scoped out back in Phase 1 for the
same reason dashboards were deferred in Phase 12: wiring a linter against skeleton
code with nothing real to check would be noise, not signal. It was never revisited once
real business logic existed in every service; a reasonable follow-up, not treated as
part of this phase.
