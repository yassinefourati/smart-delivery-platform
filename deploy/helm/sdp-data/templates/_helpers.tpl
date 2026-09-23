{{/* Common labels. part-of matches the application chart, so one selector finds both tiers. */}}
{{- define "sdpdata.labels" -}}
app.kubernetes.io/part-of: smart-delivery-platform
app.kubernetes.io/component: database
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version | replace "+" "_" }}
{{- with .name }}
sdp.io/database-cluster: {{ . }}
{{- end }}
{{- end }}

{{/*
The effective shape of one cluster: the shared `cluster:` block with the cluster's own
`overrides` deep-merged over it. mergeOverwrite on a deep copy, so one cluster's
override never leaks into the next one's render.
*/}}
{{- define "sdpdata.shape" -}}
{{- $base := deepCopy .root.Values.cluster }}
{{- $over := deepCopy (default (dict) .cfg.overrides) }}
{{- toYaml (mergeOverwrite $base $over) }}
{{- end }}

{{/* Secret names CNPG consumes (basic-auth: username + password). */}}
{{- define "sdpdata.ownerSecret" -}}{{ printf "%s-owner" .name }}{{- end }}
{{- define "sdpdata.appSecret" -}}{{ printf "%s-app" .name }}{{- end }}
{{- define "sdpdata.tlsSecret" -}}{{ printf "%s-server-tls" .name }}{{- end }}
{{- define "sdpdata.objectStore" -}}{{ printf "%s-backup" .name }}{{- end }}
{{- define "sdpdata.originStore" -}}{{ printf "%s-origin" .name }}{{- end }}

{{/*
Guards. Each refuses a render that would produce a cluster that is unsafe or cannot work,
with the reason, instead of letting the operator discover it at 3 a.m.
*/}}
{{- define "sdpdata.validate" -}}
{{- $v := .Values }}
{{- if not (has $v.mode (list "primary" "replica")) }}
{{- fail (printf "mode must be \"primary\" or \"replica\", got %q" $v.mode) }}
{{- end }}
{{- if lt (int $v.cluster.instances) 3 }}
{{- fail (printf "cluster.instances is %d. Three is the floor for this chart: a primary plus TWO standbys, so quorum synchronous replication (synchronous.number=1) still has a standby to acknowledge commits while another is down for maintenance. With two, dataDurability=required blocks every write during a routine node drain." (int $v.cluster.instances)) }}
{{- end }}
{{- if and (eq $v.cluster.synchronous.dataDurability "required") (ge (int $v.cluster.synchronous.number) (int $v.cluster.instances)) }}
{{- fail "cluster.synchronous.number must be below cluster.instances: it counts STANDBYS that must acknowledge, and there are instances-1 of them." }}
{{- end }}
{{- if $v.cluster.enableSuperuserAccess }}
{{- fail "cluster.enableSuperuserAccess=true would create a superuser password Secret. Break-glass is `kubectl cnpg psql` (RBAC-gated, pgAudit-logged); there is no reason for a replayable superuser credential to exist. See docs/security-hardening.md." }}
{{- end }}
{{- if and (eq $v.mode "replica") (not $v.backup.enabled) }}
{{- fail "mode=replica needs backup.enabled: after promotion the DR cluster must archive its own WAL, or the first failure after a failover has no recovery point." }}
{{- end }}
{{- range $name, $cfg := $v.clusters }}
{{- if $cfg.enabled }}
{{- range $k := list "database" "ownerRole" "appRole" "client" "secretPrefix" }}
{{- if not (index $cfg $k) }}
{{- fail (printf "clusters.%s.%s is required" $name $k) }}
{{- end }}
{{- end }}
{{- with $cfg.recoverFrom }}
{{- if not .cluster }}
{{- fail (printf "clusters.%s.recoverFrom.cluster is required: the cluster whose archive to restore from" $name) }}
{{- end }}
{{- if eq .cluster $name }}
{{- fail (printf "clusters.%s.recoverFrom.cluster is itself. Recover into a NEW cluster (e.g. %s-pitr) and switch the application once it is verified; restoring over the damaged cluster destroys the evidence and the fallback at once." $name $name) }}
{{- end }}
{{- if not (hasKey $v.clusters .cluster) }}
{{- fail (printf "clusters.%s.recoverFrom.cluster %q is not a cluster in this release, so its ObjectStore is not rendered here" $name .cluster) }}
{{- end }}
{{- if and .targetTime (not (regexMatch "^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?(Z|[+-][0-9]{2}:[0-9]{2})$" .targetTime)) }}
{{- fail (printf "clusters.%s.recoverFrom.targetTime %q must be RFC 3339 with a zone, e.g. 2026-09-23T14:05:00Z. A time without a zone is read in the server's zone -- the wrong instant, silently." $name .targetTime) }}
{{- end }}
{{- end }}
{{- if eq $cfg.ownerRole $cfg.appRole }}
{{- fail (printf "clusters.%s: ownerRole and appRole are both %q. The split is the point -- the runtime pool must not own the schema it reads and writes." $name $cfg.ownerRole) }}
{{- end }}
{{- end }}
{{- end }}
{{- end }}
