# Runbook: rollback, recovery and disaster recovery

This covers getting back to a known-good state after a bad release, lost or corrupted
data, a failed database, or a lost region. It's written for the on-call engineer, with
commands. The database mechanics are in [deploy/helm/sdp-data](../../deploy/helm/sdp-data/README.md),
and the reasoning is in [ADR 014](../adr/014-production-security-and-resilience.md).

## Recovery objectives

| Scenario | RPO (data lost) | RTO (time to service) | Section |
|---|---|---|---|
| Bad release, data intact | 0 | Minutes; automatic for canaried services | [Bad deploy](#bad-deploy) |
| Pod, node or zone loss | 0 | Under a minute; automatic | [Instance down](#instance-down) |
| Logical corruption or tampering | To the second before the event | 30–60 min | [Point-in-time recovery](#point-in-time-recovery) |
| Kubernetes objects deleted | 0 for data | 15–30 min | [Namespace or object loss](#namespace-or-object-loss) |
| Region loss | ≤ 5 min + S3 replication (target < 15 min) | < 1 h | [Region failover](#region-failover) |

These are **targets**. A number becomes real when a drill measures it: see
[the restore drill](#restore-drill) and record every result in its table.

## Bad deploy

Every image is a signed commit on `main`, and Argo CD deploys what `main` says. So the
rollback is a git operation, and the audit trail and the rollback are the same record.

**Canaried services (order-service):** Argo Rollouts shifts 25% → 50% → 75% of pods.
Between steps it runs an analysis of the canary pods' 5xx ratio, and it **aborts and
scales the canary away by itself** when the ratio passes the threshold. To act by hand:

```bash
kubectl argo rollouts get rollout order-service -n sdp          # where is it, what did the analysis say
kubectl argo rollouts abort order-service -n sdp                # stop now; traffic returns to stable
kubectl argo rollouts undo order-service -n sdp                 # roll the stable back one revision
```

**Everything:**

```bash
git revert <bad-commit> && git push       # via a PR; Argo CD syncs within its poll interval
# Faster, when minutes matter: roll back in Argo CD to the previous synced revision
argocd app rollback smart-delivery-platform <history-id>
# then revert in git, or selfHeal will move it forward again.
```

**Migrations.** An application rollback never needs a schema rollback, *if* migrations
follow expand/contract:
1. Add columns and tables in release N.
2. Start writing them in N.
3. Stop reading the old ones in N+1.
4. Drop the old ones in N+2.

A migration that drops or renames something release N-1 still uses makes this runbook's
rollback break the old version. Reviewers block that in the PR. Flyway does not roll
back, and it must not be made to.

## Instance down

`SdpPgInstanceDown`. CloudNativePG fails over by itself: on primary loss it promotes the
most advanced synchronous standby, typically within 30 seconds, and the `-rw` Service
follows. The services' HikariCP pools reconnect.

```bash
kubectl cnpg -n sdp-data status order-db        # topology, lag, which instance is primary
kubectl -n sdp-data get pods -l cnpg.io/cluster=order-db -o wide
```

If the old primary's pod is stuck Pending, it is usually zone capacity or a PVC bound to
a zone without nodes: `kubectl -n sdp-data describe pod`. A planned switchover, for
example before node maintenance, is `kubectl cnpg -n sdp-data promote order-db <standby-pod>`.

## No synchronous standby

`SdpPgNoSynchronousStandby`: **writes are blocked by design.** With
`dataDurability: required`, a commit waits for a standby, and there is none.

1. Get a standby back. The usual cause is a Pending pod: `kubectl -n sdp-data get pods`,
   then `describe`.
2. Only if you cannot, and only as a **recorded decision** by the incident lead, relax
   durability temporarily. Set `cluster.synchronous.dataDurability: preferred` in git and
   sync. From that moment, a primary loss can lose acknowledged commits. Revert as soon as
   a standby is back.

## Replication lag

`SdpPgReplicationLagHigh`. A lagging standby lengthens failover and can stall commits if
it is the only sync candidate. Check network and disk IOPS on its node (`kubectl top`,
the EBS volume's CloudWatch metrics). A long-running query on the standby holding back
replay shows in `kubectl cnpg -n sdp-data status`.

## WAL archiving failing

`SdpPgWalArchivingFailing` or `SdpPgWalArchiveStale`. **Every minute it lasts is data a
regional disaster would lose,** and the WAL volume is filling up.

```bash
kubectl -n sdp-data logs order-db-1 -c plugin-barman-cloud --tail=100   # the sidecar says why
```

| Error | Fix |
|---|---|
| `AccessDenied` | The IRSA role (`backup.irsaRoleArn`), or its trust policy for the `sdp-data` service account. |
| `KMS.*` | The key policy must allow the backup role. |
| Timeouts | The sdp-data NetworkPolicy's 443 egress (`networkPolicy.httpsEgressCidrs`). |

While it is broken, watch `SdpPgVolumeFilling` on the `-wal` PVC. Grow it before it fills
(`cluster.walStorage.size`), because a full WAL volume stops the database.

## Backup stale

`SdpPgBackupStale` or `SdpPgBackupMetricAbsent`.

```bash
kubectl -n sdp-data get backups --sort-by=.metadata.creationTimestamp | tail
kubectl -n sdp-data get scheduledbackups
kubectl cnpg -n sdp-data backup order-db --method plugin --plugin-name barman-cloud.cloudnative-pg.io   # take one now
```

Recovery still works from an older base backup plus WAL, just more slowly. If the alert is
`…MetricAbsent`, confirm the metric name on a live instance (`curl :9187/metrics | grep
backup`) and fix `monitoring.prometheusRule.backupTimestampMetric`.

## Connections

`SdpPgConnectionsNearLimit`. The owning service's replicas × `database.poolMax` is close
to `max_connections`. Cap the service's HPA `maxReplicas`, lower the pool, or add a CNPG
`Pooler` (PgBouncer). The app chart's NOTES print the connection arithmetic on every
install.

## Volume filling

`SdpPgVolumeFilling`. On a `-wal` PVC, check WAL archiving first. On the data PVC, grow it:
raise `cluster.storage.size` (or the cluster's override) in git and sync. CNPG expands
the PVCs online if the StorageClass has `allowVolumeExpansion: true`.

## XID wraparound

`SdpPgTransactionIdWraparoundRisk`. Find what holds the xmin horizon back, typically a
long-running or idle-in-transaction session, or an abandoned replication slot:

```sql
SELECT pid, state, xact_start, query FROM pg_stat_activity WHERE backend_xmin IS NOT NULL ORDER BY xact_start;
SELECT slot_name, active, xmin FROM pg_replication_slots;
```

Terminate the offender, and let autovacuum (or a manual `VACUUM (FREEZE)`) catch up.

## Point-in-time recovery

Use this for logical corruption: a bad `UPDATE`, a buggy migration, or tampering. **Never
restore over the damaged cluster.** Restore into a new one, verify it, then switch the
application.

1. **Stop the damage.** Scale the writer down, or quarantine the pod
   ([incident runbook](incident-response.md#database-tampering)).
2. **Find the target time**, just before the first bad statement. Get it from the
   pgAudit log (`{namespace="sdp-data"} | json | logger="pgaudit"`), the application logs
   for the request's `correlationId`, or the time the alert fired. Use UTC, RFC 3339.
3. **Declare the recovery cluster** in git, next to the others in
   `deploy/helm/sdp-data/values.yaml` (template:
   [`examples/pitr-order-db.yaml`](../../deploy/helm/sdp-data/examples/pitr-order-db.yaml)):

   ```yaml
   order-db-pitr:
     enabled: true
     database: order_db
     ownerRole: order_owner
     appRole: order_app
     client: order-service
     secretPrefix: prod/sdp/order-service
     recoverFrom:
       cluster: order-db
       targetTime: "2026-09-23T14:05:00Z"   # replay stops just before this instant
   ```

   The chart refuses a target time without a zone, and a recovery over the source
   cluster itself. Argo CD creates `order-db-pitr` with its own TLS certificate, network
   policy and backups. Recovery time is dominated by base-backup download plus WAL replay:
   `kubectl cnpg -n sdp-data status order-db-pitr`.
4. **Verify before switching.** Break-glass, read-only:
   `kubectl cnpg -n sdp-data psql order-db-pitr -- -d order_db`. Check that the bad change
   is absent and that the newest good rows are present.
5. **Switch the application.** In `values-production.yaml`:
   - set order-service's `DB_URL` host to `order-db-pitr-rw.sdp-data.svc`;
   - set `networkPolicy.egress.postgres.clusters.order-service: order-db-pitr`.

   Then sync. Argo Rollouts canaries the change like any other.
6. **Reconcile the gap.** Transactions after the target time that were legitimate are not
   in the recovered cluster. For order-service, the outbox events already on Kafka and
   the other services' databases are the record to reconcile against. Decide per case,
   and write it down.
7. **Afterwards.** Keep `order-db` (fenced: `kubectl cnpg -n sdp-data fencing on order-db "*"`)
   until the investigation is done. Then delete it and, if you want the old name back,
   repeat the procedure in the other direction during a calm period.

## Namespace or object loss

Deleted Deployments, ConfigMaps or policies come back from git: an Argo CD sync recreates
everything the charts declare. Velero covers what git does not, and the state as it was:

```bash
velero backup get
velero restore create --from-backup sdp-daily-<timestamp> --include-namespaces sdp
```

- **Secrets are not in Velero, on purpose.** ESO re-syncs them from Secrets Manager
  within `refreshInterval`, or immediately with the `force-sync` annotation.
- **Database volumes are not in Velero either.** A deleted `Cluster` object is recovered
  with [point-in-time recovery](#point-in-time-recovery) from its archive, into a
  cluster of the same or a new name.
- **Argo CD never prunes the database tier.** The `sdp-data` Application has `prune:
  false`, so removing a Cluster from git does not delete the database.

## Region failover

The DR region runs `sdp-data` with `values-dr.yaml`. Each cluster there is a read-only
replica, replaying the primary region's WAL archive, which S3 Cross-Region Replication
copies over. **Promotion is a human decision.** Two writable regions for the same data is
worse than a short outage, so nothing promotes automatically.

1. **Declare the primary region lost.** Confirm with AWS Health and your own probes that
   it is not merely degraded. If it can still write, stop it from writing first (scale the
   services to zero there), or the regions will diverge.
2. **Check how far behind the DR region is:** `kubectl cnpg -n sdp-data status <cluster>`
   shows the last replayed WAL and its timestamp. That gap, plus anything not yet
   replicated to S3, is the data loss. Record it.
3. **Promote.** In the DR region's values, set `dr.promoted: true` and sync. Each cluster
   stops following the archive, becomes a writable primary, starts managing its roles
   (ExternalSecrets), and begins its own base backups to the DR bucket.
4. **Start the application in the DR region.** Sync the same `smart-delivery-platform`
   Application against the DR cluster. The `DB_URL`s are unchanged: the Service names are
   the same in both regions.
5. **Move traffic.** Switch the Route 53 failover record, or flip the global load
   balancer, to the DR region's ingress.
6. **Fail back, later and deliberately.** Rebuild the old region as the replica: install
   `sdp-data` there with `mode: replica`, pointing `dr.sourceDestinationPath` at the DR
   region's archive. Let it catch up, then repeat steps 1–5 in the other direction during
   a maintenance window.

## Restore drill

A backup that has never been restored is a hope. **Run this quarterly, and after any
change to the backup configuration.**

1. Pick a cluster, rotating through the six, and a random target time from the past week.
2. Recover it into `<cluster>-drill` with `recoverFrom`
   ([point-in-time recovery](#point-in-time-recovery), steps 3–4), in staging or in a
   scratch namespace in production. Never switch the application to it.
3. Measure the time from the sync to the cluster being ready, and check data at the
   target time: row counts, the newest `created_at`, and a known record.
4. Delete the drill cluster, and write the result below.

| Date | Cluster | Target time | Time to ready | Data verified | By |
|---|---|---|---|---|---|
| (first drill due after the first production install) | | | | | |

Also do a region failover drill once a year, in a staging pair of regions, and time it
against the RTO above.
