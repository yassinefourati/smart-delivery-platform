# deploy/

Kubernetes deployment artifacts. One chart lives here:

- **[`helm/smart-delivery-platform/`](smart-delivery-platform/README.md)** -- the
  eight platform services (and, optionally, the React SPA) as a single Helm release.

## Read this before using it

`docs/ci-cd.md` says, under "What's deliberately not here":

> There is no Kubernetes manifest, Helm chart, or cloud environment anywhere in this
> repository to deploy *to*; adding a "deploy" job would mean inventing
> infrastructure this project doesn't have and pointing at a target that doesn't
> exist.

Half of that is now out of date and half of it still holds, and the difference
matters:

- **There is a chart.** It is reviewable, it renders, and it encodes the probe,
  shutdown, resource, secret and observability decisions this platform made
  deliberately rather than leaving them to whoever first runs `kubectl`.
- **There is still no cluster, and still no deploy job.** Nothing in
  `.github/workflows/ci.yml` applies this chart, because there is nothing to apply
  it to. Publishing images to GHCR remains the end of the pipeline. A `deploy` job
  pointed at a target that does not exist would be worse than no job at all -- that
  reasoning has not changed.
- **The chart has never been applied to a real cluster.** `helm lint` and
  `helm template` pass; no kubelet has read one of its probe stanzas. The chart's
  own README has the full list of what was and was not verified, and the ten known
  gaps it does not fix.

`docker-compose.yml` is still the way to *run* this platform locally
(`docs/local-development.md`), and the Postgres, Kafka and Redis definitions in it
are development-only. This chart runs none of the three: it expects them to exist
already, as managed services or as operator-managed clusters.
