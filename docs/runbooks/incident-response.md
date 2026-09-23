# Runbook: security incident response

For the on-call engineer, when a `category: security` alert pages or someone reports
something that looks like an attack. It's a procedure, with commands; the reasoning is
in [docs/security-hardening.md](../security-hardening.md) and
[ADR 014](../adr/014-production-security-and-resilience.md).

Four rules take precedence over anything below:

1. **Contain before you investigate, and isolate before you delete.** A deleted pod takes
   its process list, open connections and written files with it.
2. **Write down what you do, as you do it.** Keep a timestamped log in the incident
   channel. You will not remember the order afterwards, and the order matters.
3. **Assume the credentials the workload could reach are compromised** until shown
   otherwise. That means its database role, its Kafka access, and the service-client
   secret if it is user-service or order-service.
4. **Escalate early.** Paging the security lead for something that turns out to be a bad
   deploy costs little; the reverse can cost a lot.

## Detect

| Signal | Source | What it usually means |
|---|---|---|
| `SdpAuthFailureSpike` | Prometheus (app chart) | Credential stuffing at `/api/v1/auth/login`, or replayed forged or expired tokens. A deploy that broke JWT verification looks the same. |
| `SdpForbiddenSpike` | Prometheus | One caller walking other users' order, address or delivery ids (IDOR probing). The ownership checks are saying no. |
| `SdpBusyWithoutTraffic` | Prometheus | CPU with no requests to explain it: the crypto-miner shape. |
| `SdpConcurrentOrderReplays` | Prometheus | A client duplicating requests, or someone replaying captured ones. |
| `SDP JVM spawned a shell or network tool` | Falco → Alertmanager | **Remote code execution.** The services never do this. Treat it as confirmed. |
| `SDP service account token read…`, `…package manager…`, `…mining port` | Falco | Post-exploitation: reconnaissance, tooling, monetisation. |
| `SdpPgAuthenticationFailures`, `SdpPgHbaRejection` | Loki | Password guessing against a database, or a connection from somewhere the network policy should make impossible. |
| `SdpPgUnexpectedDDL` | Loki (pgAudit) | Schema changed by something other than a Flyway owner role. |
| Kyverno `PolicyViolation` events | Kubernetes events | An unsigned or foreign image was attempted: a supply-chain attempt or a misconfigured pipeline. |

First look, in this order:

```bash
# What is the pod doing, and since when?
kubectl -n sdp get pods -o wide -l app.kubernetes.io/part-of=smart-delivery-platform
kubectl -n sdp describe pod <pod>
kubectl -n sdp logs <pod> --since=1h | tail -200          # structured JSON; correlationId ties a request together
# Falco's view of it (Loki / Grafana Explore):
#   {priority=~"Critical|Error|Warning"} |= "<pod>"
# Who called the Kubernetes API about it (EKS audit log in CloudWatch Logs Insights):
#   fields @timestamp, user.username, verb, objectRef.resource, objectRef.name
#   | filter objectRef.namespace = "sdp" | sort @timestamp desc | limit 100
```

## Triage

Decide which of these it is. That decision sets how hard you contain.

- **False positive or benign:** a load test, a new client retrying, a deploy. Close it
  with a note. If it will recur, tune the threshold in `values-production.yaml`; don't
  silence the alert.
- **Probing without success:** 401/403 spikes and no Falco signal. Go to
  [Credential attack](#credential-attack).
- **Compromised workload:** any Falco critical, or `SdpBusyWithoutTraffic` confirmed.
  Go to [Contain](#contain) **now**.
- **Data tampering:** unexpected DDL, or data that is wrong. Go to
  [Database tampering](#database-tampering).

## Contain

### Isolate a pod without destroying it

```bash
POD=<pod>

# 1. Cut ALL its traffic, in and out, immediately.
kubectl -n sdp label pod "$POD" security.sdp/quarantine=true --overwrite
```

Why this works when a NetworkPolicy "deny" normally cannot: policies are additive, so a
deny-all policy alone would be overridden by the existing allow policies. The chart
therefore excludes `security.sdp/quarantine` from every allow policy: the service's
ingress, its egress allow-list, and the database's port 5432 in `sdp-data`. So a
quarantined pod matches only the quarantine policy, which allows nothing. Requires
`networkPolicy.enabled` (on in production).

```bash
# 2. Take it out of its Service and its ReplicaSet, so a clean replacement starts and no
#    user traffic is routed to it. Changing a selector label orphans the pod: the
#    ReplicaSet (or Rollout) no longer owns it, and will not delete it.
kubectl -n sdp label pod "$POD" app.kubernetes.io/name=quarantined --overwrite

# 3. Capture evidence while it is still running.
mkdir -p "incident-$(date -u +%Y%m%dT%H%MZ)" && cd "$_"
kubectl -n sdp get pod "$POD" -o yaml > pod.yaml
kubectl -n sdp logs "$POD" --all-containers --timestamps > logs.txt
kubectl -n sdp logs "$POD" --all-containers --timestamps --previous > logs-previous.txt 2>/dev/null || true
kubectl -n sdp get events --field-selector involvedObject.name="$POD" -o yaml > events.yaml
```

To look inside the pod you need break-glass (`pods/exec` and ephemeral containers are
not granted to anyone else). A debug container shares the process namespace without
modifying the target:

```bash
# --target is the container, which is named after the service (e.g. order-service).
kubectl -n sdp debug "$POD" -it --image=busybox:1.37 --target=<service-name> --profile=restricted -- sh
#   ps -ef                 # anything besides PID 1 java is suspect
#   cat /proc/1/cmdline | tr '\0' ' '
#   ls -la /proc/1/root/tmp /proc/1/root/app   # the only writable path is /tmp
#   netstat -tnp 2>/dev/null || cat /proc/net/tcp
```

For a full forensic copy, snapshot the node's EBS volume, then cordon and drain the node
so nothing else lands on it.

```bash
NODE=$(kubectl -n sdp get pod "$POD" -o jsonpath='{.spec.nodeName}')
kubectl cordon "$NODE"
```

### Contain a whole service

Scale the service to zero or roll back (see
[the DR runbook](disaster-recovery.md#bad-deploy)). If the service must keep running,
quarantine pods one at a time while the ReplicaSet replaces them. Emergency deny rules
are ordinary NetworkPolicies, and the `sdp:oncall` role may create them.

### Credential attack

**Symptoms:**
- `SdpAuthFailureSpike` at the edge;
- `SdpPgAuthenticationFailures` or `SdpPgHbaRejection` at a database.

**At the edge:**
1. Find the sources in the gateway access logs (Loki: `{namespace="sdp",
   app_kubernetes_io_name="api-gateway"} |= "401"`), grouped by client IP.
2. Block the offending CIDRs at the load balancer or WAF, not in the application.
3. If one account is targeted, disable it (ADMIN API) and contact the owner.

`/api/v1/auth/login` has no lockout or rate limit yet ([security.md](../security.md#deliberately-out-of-scope-follow-ups)).
The WAF rate rule is the control until it does.

**At a database:** port 5432 admits only the owning service's pods. A failure there means
a compromised pod of that service guessing, or a network-policy gap.
1. Find the source address in the Postgres log line, and map it to a pod with
   `kubectl get pods -A -o wide | grep <ip>`.
2. Quarantine that pod.
3. Rotate that database's credentials (below).

### Database tampering

`SdpPgUnexpectedDDL` or wrong data.

1. **Stop the writer.** Scale the owning service to zero (`kubectl -n sdp scale
   deploy/<svc> --replicas=0`; for order-service `kubectl argo rollouts pause order-service`
   and then scale), or quarantine the pod responsible.
2. **Keep the evidence.** Take an on-demand backup now:
   `kubectl cnpg -n sdp-data backup <cluster> --method plugin --plugin-name barman-cloud.cloudnative-pg.io`.
   The WAL archive already covers the time before it. The pgAudit lines in Loki show
   who ran what: `{namespace="sdp-data"} | json | logger="pgaudit"`.
3. **Recover.** Restore to just before the first bad statement into a new cluster, then
   switch over: [point-in-time recovery](disaster-recovery.md#point-in-time-recovery).
   Never restore over the damaged cluster; it is both your evidence and your fallback.

## Eradicate

- **Rotate every credential the compromised workload could read**, with the procedures
  below.
- **Find the entry point.** Check the image digest the pod ran (in `pod.yaml`) against
  the CI run that built it: `cosign verify` gives the workflow run in the certificate.
  Check the dependency named in the Falco event or stack trace against the SBOM:
  `cosign verify-attestation --type spdxjson … | jq`.
- **Patch, and ship through CI.** Only a CI-signed image from `main` can run, so the fix
  goes through a PR. That is the point.
- **If the image itself is bad** (a malicious dependency, or a compromised build), revert
  to the previous good commit (`git revert`; Argo CD syncs it). Until every pod has moved,
  block the bad digest: add a Kyverno rule denying `image` values containing that digest.

### Rotating a database password

Both the service's Secret and CNPG's role Secret are synced from the same Secrets
Manager key, so a rotation is one write:

```bash
SVC=order-service CLUSTER=order-db
# 1. New password in the secret manager (never on a command line in shell history):
aws secretsmanager put-secret-value --secret-id "prod/sdp/$SVC/db-app" \
  --secret-string file://new-db-app.json        # {"username":"order_app","password":"..."}
# 2. Sync now rather than within refreshInterval: database side first, so the role
#    has the new password before any pod tries it.
kubectl -n sdp-data annotate externalsecret "$CLUSTER-app" force-sync="$(date +%s)" --overwrite
kubectl -n sdp-data get cluster "$CLUSTER" -o jsonpath='{.status.managedRolesStatus}' ; echo
kubectl -n sdp annotate externalsecret "sdp-${SVC%-service}-db" force-sync="$(date +%s)" --overwrite
# 3. Restart the pods; environment variables are read once, at start.
kubectl -n sdp rollout restart deploy/"$SVC"     # order-service: kubectl argo rollouts restart order-service
```

Between steps 2 and 3, connections already in the pool keep working, but a new
connection with the old password is refused. Run step 3 promptly. For the owner role
(`db-migrator`), do the same with `$CLUSTER-owner` and `sdp-<svc>-db-migrator`. For a
zero-error rotation, create a second login role, move the service to it, then drop the
first.

### Rotating the JWT signing key

- **Routine rotation** keeps existing tokens valid ([security.md](../security.md#key-rotation)):
  1. Put the new PEM in `prod/sdp/user-service/jwt-signing`.
  2. Set a new `JWT_SIGNING_KID` in `values-production.yaml`.
  3. Add the old public key as `JWT_RETIREDKEYS_0_KID` / `JWT_RETIREDKEYS_0_PUBLICKEY`.
  4. Deploy.
  5. Remove the retired entry one token lifetime later.
- **After a compromise, skip the retired-key step.** With the old key dropped, every
  token it signed stops verifying at once. Every user and every service token is logged
  out, and that's the intent: an attacker holding the key could have minted anything.
  Resource servers refetch the JWKS on the unknown `kid` without a restart.

### Rotating the service-client secret

`prod/sdp/order-service/client` feeds both user-service (which checks it) and
order-service (which presents it). Update it in the secret manager, then force-sync
`sdp-service-clients`. Restart user-service first, then order-service; order-service
fails its token fetch in between, which its circuit breaker absorbs.

## Recover

- Bring the service back through the normal path: an Argo CD sync of a reviewed commit.
- Watch `SdpHighErrorRate` and the security alerts for at least one window after.
- Delete quarantined pods only once the evidence has been copied off the cluster, with
  `kubectl -n sdp delete pod -l security.sdp/quarantine=true`.
- Uncordon the node, or replace it. For a confirmed compromise, replace it.

## Runtime alert

For any Falco event: open the event (Grafana → Loki, or the Alertmanager payload). It
names the rule, pod, image and command line. For CRITICAL, go to [Contain](#contain). For
WARNING (an interactive shell), check whether a break-glass session was open at that
time (CloudTrail `AssumeRole` of `sdp-break-glass`). If none was, treat it as CRITICAL.

## Post-incident

Within five working days, write a blameless review covering:
- the timeline, from the log kept above;
- the root cause;
- what detected it and how fast;
- what would have detected it sooner;
- the actions, each with an owner.

Every "we could not tell" in the review becomes an alert, a log field or a runbook line,
and this file gets updated in the same PR.
