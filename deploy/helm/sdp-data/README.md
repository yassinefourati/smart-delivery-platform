# sdp-data: the PostgreSQL tier on CloudNativePG

This chart holds one highly available PostgreSQL cluster per schema-owning service. The
decision and its alternatives are in [ADR 014](../../../docs/adr/014-production-security-and-resilience.md).
How it fits the rest of the hardening is in [docs/security-hardening.md](../../../docs/security-hardening.md).

| Cluster | Database | Owner (Flyway) | Runtime role | Only client |
|---|---|---|---|---|
| user-db | user_db | user_owner | user_app | user-service |
| product-db | product_db | product_owner | product_app | product-service |
| inventory-db | inventory_db | inventory_owner | inventory_app | inventory-service |
| order-db | order_db | order_owner | order_app | order-service |
| payment-db | payment_db | payment_owner | payment_app | payment-service |
| delivery-db | delivery_db | delivery_owner | delivery_app | delivery-service |

notification-service has no database and never gets one.

## What each cluster is

- **Three instances, one per availability zone.** Pod anti-affinity on
  `topology.kubernetes.io/zone` is *required*, not preferred.
- **Quorum synchronous replication.** A commit returns once any one standby has it, so
  losing a node or a whole zone loses nothing (RPO 0). With `dataDurability: required`
  and both standbys gone, writes block rather than silently going asynchronous.
- **TLS only.** The server certificate comes from one private CA via cert-manager: ECDSA
  P-256, a new key on every renewal, reloaded live.
  - `pg_hba` rejects every non-TLS connection before anything else can match.
  - TLS 1.3 minimum and SCRAM-SHA-256 only.
  - The services connect with `sslmode=verify-full`, so a spoofed endpoint cannot
    collect a password.
- **Two roles per database.** The owner runs Flyway and owns every object. The runtime
  role gets DML only through `<db>_rw` and default privileges: it cannot create, drop,
  truncate or alter.
  - There is no superuser password.
  - Break-glass is `kubectl cnpg psql`, which is RBAC-gated, and `pgaudit.log=all`
    applies to that role.
- **pgAudit.** All DDL and role and privilege changes, by anyone. Parameters are not
  logged, because that's where the PII is.
- **PITR.** WAL is archived continuously, at least every 5 minutes on a quiet database.
  A base backup runs daily, staggered, and from a standby.
  - Both go to S3 through the Barman Cloud plugin with IRSA.
  - Server-side KMS encryption is on.
  - The bucket has Object Lock in compliance mode, and the role that writes backups is
    not allowed to delete them.
- **DR.** Install this chart in a second region with `values-dr.yaml`. Each cluster there
  replays the primary region's replicated WAL archive. Promotion is `dr.promoted=true`;
  see [the DR runbook](../../../docs/runbooks/disaster-recovery.md).
- **Isolation.** The namespace is default-deny. Port 5432 is reachable only from the one
  owning service's pods, and only while they are not quarantined. Egress is DNS, the
  cluster's own peers, and 443 with the metadata endpoints carved out.
- **Alerts.** Ten rules, each with a runbook anchor:
  - instance down;
  - no synchronous standby (writes blocked);
  - replication lag;
  - WAL archiving failing or stale;
  - backup stale, and the backup metric being absent;
  - connections near the limit;
  - volume filling;
  - XID wraparound.

## Recovery objectives

| Scenario | RPO | RTO | Mechanism |
|---|---|---|---|
| Pod or node loss | 0 | under a minute | CNPG automatic failover to the synchronous standby |
| Zone loss | 0 | under a minute | Same; the standbys are in other zones |
| Bad deploy or migration, data intact | 0 | minutes | Roll back the application (Argo Rollouts / `helm rollback`); migrations are forward-only, see the runbook |
| Logical corruption (bad `UPDATE`, attacker) | seconds before the event | 30–60 min | PITR into a new cluster, verify, switch the application over |
| Region loss | ≤ 5 min + S3 replication delay (typically < 15 min) | < 1 h | Promote the DR region's replica clusters |
| Account compromise, backups targeted | as above | hours | Object Lock: the backups cannot be deleted before retention ends, even with admin credentials |

These are design targets. They become real numbers only after a restore drill measures
them, and the runbook says to record each drill's result.

## What has been verified, and what has not

**Verified here:**
- `helm lint` passes, and the chart renders in all three modes (primary, DR replica, DR
  promoted).
- Every rendered object validates with `kubeconform -strict` against the Kubernetes 1.30
  schemas plus the real CRD schemas for CloudNativePG, the Barman Cloud plugin,
  cert-manager, ESO and the Prometheus Operator: 50/38/56 resources, 0 invalid, 0 skipped.
- `promtool check rules` passes on the alert rules.
- Each guard in `_helpers.tpl` was fired on purpose.
- The role and grant SQL in `templates/cluster.yaml` was run against PostgreSQL with
  user-service's real Flyway migrations and live traffic. Flyway ran as the owner, which
  owns every table. The runtime pool connected as the app role, which was refused
  CREATE, DROP and TRUNCATE. Registration and login worked.

**Not verified, because no Kubernetes cluster was available:**
- No `helm install`. No operator has reconciled these objects.
- No failover has been triggered.
- No backup has been taken or restored.
- The Barman Cloud plugin's backup-age metric name comes from its documentation. The
  `SdpPgBackupMetricAbsent` alert exists so that a wrong name is loud rather than silent.

Treat the first install as the first test, and do the restore drill before relying on
any of it.
