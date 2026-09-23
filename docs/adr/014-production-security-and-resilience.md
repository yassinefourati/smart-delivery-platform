# ADR 014: A production security and resilience baseline, declared in the repository

## Status
Accepted (Phase 22)

## Context

After Phase 21 the platform was correct and observable, and it had a Helm chart. It was
not defensible, and it could not be recovered. A security review of the Phase 20 chart,
modelled on a real EKS deployment, found these gaps.

**Network.** Pods had ingress rules only, so egress was unrestricted: a compromised pod
could reach any service, any database, the internet and the cloud metadata endpoint.
There was no way to isolate one pod during an incident without deleting the evidence
along with it.

**Database.** The chart assumed a Postgres existed and said nothing about it:
- no TLS verification: the JDBC URL had no `sslmode`, so a spoofed endpoint would
  receive the password;
- one credential per service that also owned the schema, so an injection could `DROP`
  what it could read;
- no audit trail;
- no stated backup, point-in-time recovery or cross-region story.

**Supply chain.** CI scanned images, then published them unsigned. Nothing in a cluster
could tell a CI-built image from any other image pushed under the same name.

**Secrets.** They were Kubernetes Secrets created by hand (NOTES.txt printed the
`kubectl` commands). There was no source of truth outside the cluster, and rotation
meant editing etcd.

**Detection and response.** Alerts covered availability. There was nothing for credential
attacks, privilege probing, crypto-mining, runtime compromise or unexpected DDL, and no
written procedure for any of them.

**Release safety.** A bad version went to 100% of pods, and only a human noticing and
running `helm rollback` could undo it.

## Decision

Everything that makes the platform defensible and recoverable is declared in this
repository, validated in CI, and documented with the reasoning. None of it is a
checklist item in someone's head. Concretely:

1. **Application chart** (`deploy/helm/smart-delivery-platform`):
   - **Egress allow-lists** per service: DNS, its own database cluster, its Kafka and
     Redis peers, and OTLP. Plus an optional namespace default-deny.
   - **A quarantine label** that every allow policy excludes, so one
     `kubectl label pod X security.sdp/quarantine=true` cuts a pod off while keeping
     it, and its memory and filesystem, for forensics.
   - **ExternalSecrets** from AWS Secrets Manager through one ClusterSecretStore
     restricted to our namespaces.
   - **`sslmode=verify-full`** against a private CA distributed by trust-manager.
   - **Separate Flyway credentials** (`DB_MIGRATION_USERNAME`), so the runtime pool
     does not own the schema.
   - **Security alerts** (auth-failure and forbidden spikes, CPU without traffic,
     concurrent idempotent-replay spikes), routed by a `category` label.
   - **Canary releases** of order-service through Argo Rollouts, which aborts
     automatically on a canary-only 5xx ratio.
2. **A database tier** (`deploy/helm/sdp-data`) on **CloudNativePG**:
   - one cluster per service, three instances across three zones, quorum synchronous
     replication;
   - TLS-only `pg_hba`, SCRAM, no superuser password;
   - an owner/runtime role split whose grants were exercised against real migrations
     and traffic;
   - pgAudit for DDL and role changes;
   - continuous WAL archiving plus daily base backups to S3 through the Barman Cloud
     plugin (IRSA, SSE-KMS), with Object Lock in compliance mode and a backup role that
     cannot delete;
   - a replica-cluster mode for a second region.
3. **A cluster baseline** (`deploy/cluster/`):
   - Pod Security `restricted` on the application and data namespaces;
   - least-privilege RBAC with break-glass;
   - Kyverno: signature and SBOM verification against this repo's main-branch CI
     identity, a registry allow-list, no `:latest`, resources;
   - Falco with SDP-specific rules;
   - Velero for objects;
   - Argo CD pulling from `main`;
   - Alertmanager routing and Loki log alerts;
   - the immutable backup bucket.
4. **CI**:
   - keyless cosign signing of every pushed digest, plus a signed SPDX SBOM
     attestation;
   - one validation script, `scripts/validate-k8s.sh`, that lint-renders every mode,
     validates everything against the real CRD schemas with nothing skipped, fires
     every guard, parses every alert rule and runs the admission policies.
5. **Runbooks**: `docs/runbooks/incident-response.md` and
   `docs/runbooks/disaster-recovery.md`, written as procedures with commands.

### Why CloudNativePG, not RDS/Aurora

RDS would remove the operating burden. With CloudNativePG:
- The failover, backup, PITR and DR model is **declared in the repo and reviewed like
  code**.
- It is identical in every environment, so a restore drill in staging exercises the
  production mechanism.
- It is portable across clouds.

The cost is that we run Postgres. That's a real cost, and the reason `sdp-data` is a
separate chart: a team that picks RDS skips it, and the application chart only needs a
`DB_URL`, a CA and two credentials either way. Section 2 of
`docs/security-hardening.md` applies to both.

### Why a separate database per service stays a separate *cluster*

ADR 001 put each service's data in its own database. With separate clusters:
- The network policy on 5432 names exactly one client.
- A noisy tenant cannot exhaust another's connections.
- One service's restore does not roll back the others.
- The blast radius of a compromised database credential is one service.

A shared cluster with six databases would be cheaper, and it would fall short of every
one of these.

### Why quorum synchronous replication with `dataDurability: required`

It gives RPO 0 for node and zone loss at one cross-AZ round trip per commit. With both
standbys gone, writes block instead of silently going asynchronous. For orders and
payments, a short write outage is the lesser failure, compared with acknowledging
commits that a second failure would lose.

### Why keyless signing

A signing key is a long-lived secret that must be stored, rotated and protected, and
whose theft is silent. Keyless signing ties each signature to the workflow's OIDC
identity and logs it publicly in Rekor. The admission policy pins the *identity* (this
repo, this workflow file, `refs/heads/main`), which a fork, a PR build or a stolen
registry token cannot produce.

## Consequences

- **The platform needs these controllers:** cert-manager, trust-manager, ESO, Kyverno,
  Falco, CloudNativePG with the Barman Cloud plugin, Argo CD, Argo Rollouts, Velero, and
  the Prometheus/Loki stack. That's a lot of moving parts to run. Each one replaces a
  manual procedure that would otherwise be skipped under pressure.
- **Kyverno's signature policy fails closed** (`failurePolicy: Fail`). If Kyverno is down,
  no new pod starts in `sdp`, so Kyverno itself needs replicas and a PDB. The trade is
  deliberate: failing open would make the policy optional in exactly the situations
  where it matters.
- **Only CI on `main` can produce a runnable image.** A hotfix goes through a PR and
  CI, or through break-glass, which is audited.
- **Backups cannot be deleted early, by anyone.** A mistaken backup of the wrong data
  stays, and is billed, until retention ends. Test Object Lock on a scratch bucket first.
- **Two sets of database credentials per service.** The migrator's is used for seconds
  per deploy and could be removed from the pods entirely by a pre-deploy migration Job.
  The chart's README explains why that Job isn't used yet (the images have no
  migrate-and-exit mode).
- **Several parts are unverified**, as stated in each README:
  - nothing has been applied to a cluster;
  - no failover, backup or restore has been run;
  - the Falco rules have not been fired;
  - the Barman plugin's backup-age metric name has not been observed.

  What has been verified is schema validity, render guards, rule syntax, admission
  behaviour on rendered manifests, and the database role split against a real Postgres.
  The first staging install and the first restore drill are the tests of the rest.

## Alternatives considered

- **Service mesh (Istio, Linkerd) for mTLS and L7 policy.** It would give encrypted,
  authenticated pod-to-pod traffic and per-route authorization. It was rejected for now
  because of the operational weight relative to the gain: service-to-service calls
  already carry signed JWTs (ADR 007), and NetworkPolicy plus TLS to the database cover
  the highest-value paths. It remains the next step if a regulator requires encryption
  of all in-cluster traffic.
- **OPA Gatekeeper instead of Kyverno.** It is equivalent for validation. Kyverno
  verifies cosign signatures and attestations natively and mutates to the digest;
  Gatekeeper needs an external data provider for that.
- **Sealed Secrets or SOPS in git instead of ESO.** They keep secrets in the repository,
  encrypted. They were rejected because rotation becomes a commit and a sync, the
  decryption key becomes the new crown jewel, and there is no access log per read. A
  secret manager has all three.
- **Velero volume snapshots for the database.** They are crash-consistent block copies
  with no point-in-time capability, a second copy that diverges from the real backup,
  and restore ambiguity. CNPG's base backup plus WAL is strictly better for Postgres;
  Velero keeps the objects.
- **Automatic cross-region failover.** It was rejected: when regions disagree, two
  writable primaries are worse than a short, decided outage. Promotion is a one-value
  change, `dr.promoted=true`, following a runbook.

## Reversal

Every piece is opt-in in the charts (`networkPolicy.restrictEgress`,
`externalSecrets.enabled`, `database.tls.enabled`, `rollouts.enabled`, sdp-data as a whole)
and defaults to off in `values.yaml`. Local development and the Compose stack are
unchanged. Reverting a piece is a values change, except the Object Lock retention on
backups already written, which by design cannot be reverted.
