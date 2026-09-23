# deploy/cluster: the cluster-level security baseline (Phase 22)

This directory holds the objects that live outside the application's namespaces, and
the third-party controllers that turn policy into enforcement. The platform team
applies it once per cluster; the application charts depend on it being there.

The reasoning for each piece is in
[docs/security-hardening.md](../../docs/security-hardening.md). The decision is recorded
in [ADR 014](../../docs/adr/014-production-security-and-resilience.md).

## Install order

Each step depends on the ones before it.

| # | What | How | Why here |
|---|---|---|---|
| 1 | Namespaces with Pod Security levels | `kubectl apply -f 00-namespaces.yaml` | Everything else is created inside these. `restricted` is enforced from the first pod. |
| 2 | cert-manager, trust-manager | upstream Helm charts | Issue and distribute the Postgres CA. |
| 3 | Postgres CA chain and trust bundle | `kubectl apply -f cert-manager/` | The `sdp-postgres-ca` issuer, plus the ConfigMap the services mount as `sslrootcert`. |
| 4 | External Secrets Operator (IRSA) | upstream chart, then `kubectl apply -f external-secrets/` | The only path from AWS Secrets Manager into the cluster. |
| 5 | Kyverno | upstream chart, then `kubectl apply -f kyverno/` | Signature, registry and tag policies. **Apply before step 9**, or unverified pods get in first. |
| 6 | Falco and falcosidekick | `helm upgrade --install falco falcosecurity/falco -n security -f falco/values.yaml` | Runtime detection. |
| 7 | kube-prometheus-stack, Loki | upstream charts, then `kubectl apply -f observability/` | Alert routing by `category`, plus log-based detection. |
| 8 | CloudNativePG and the Barman Cloud plugin | upstream manifests or charts | The database operator. |
| 9 | Argo CD, then the Applications | upstream chart, then `kubectl apply -f argocd/` | `sdp-data` (wave 0), then the application (wave 1), both pulled from `main`. |
| 10 | Velero | upstream chart, then `kubectl apply -f velero/` | Object-level backups of the two namespaces. |
| — | Backup buckets | `backup-bucket/create-backup-bucket.sh` (dry run by default) | Object Lock (compliance), SSE-KMS, TLS-only, cross-region replication, and a backup role that cannot delete. |
| — | RBAC | `kubectl apply -f rbac/` | Read-only developers, a scoped incident-responder role, and break-glass with no standing members. |

## Contents

```
00-namespaces.yaml                 PSS restricted (sdp, sdp-data), baseline, privileged (security)
rbac/                              developer view, incident responder, break-glass
kyverno/                           signed images + SBOM attestation, registry allow-list,
                                   no :latest, requests/limits (Audit)
falco/values.yaml                  modern eBPF, falcosidekick -> Alertmanager + Loki,
                                   5 SDP rules (JVM spawning a shell, SA-token reads, mining ports, ...)
external-secrets/                  ClusterSecretStore for AWS Secrets Manager, restricted to sdp/sdp-data
cert-manager/                      private CA for Postgres, trust-manager Bundle -> ConfigMap
velero/                            daily Schedule, no volume snapshots (CNPG owns database backups)
argocd/                            AppProject (namespaced resources only), two Applications
observability/                     AlertmanagerConfig (routes on `category`), Loki ruler alerts
backup-bucket/                     immutable S3 bucket script, plus the no-delete IAM policy
```

## What has been verified

- **Manifests.** Every YAML manifest here validates with `kubeconform -strict` against
  the Kubernetes 1.30 schemas and the upstream CRD schemas. That covers Kyverno,
  cert-manager, trust-manager, ESO v1, Velero, Argo CD and the Prometheus Operator.
  None are skipped.
- **Kyverno policies.** Applied with the Kyverno CLI (v1.19) to the rendered production
  chart:
  - every workload passes;
  - each negative case is refused: a foreign registry, `:latest`, an untagged image, a
    container without resources;
  - the signature policy cannot be exercised offline, because it needs a signed image
    and Rekor.
- **Loki rules.** Parsed by `lokitool rules lint`, and a deliberately broken expression is
  rejected.
- **Falco rules.** Checked in CI by `falco -V` against the default ruleset. That proves
  they load, not that they fire.
- **Bucket script.** Passes `shellcheck`. A dry run and a stubbed `--apply` run emit valid
  JSON for every AWS call and for the IAM policy.

**Not verified:** none of this has been applied to a cluster, and no AWS call has been
made. Treat the first install in a staging cluster as the test. The Falco rules need
`falcosecurity/event-generator` to show they actually fire.
