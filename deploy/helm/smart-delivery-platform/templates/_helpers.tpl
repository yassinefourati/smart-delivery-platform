{{/*
================================================================================
Named templates. Nothing in here renders a manifest -- these are the pieces every
template shares, plus the validation guards that fail an install early instead of
letting it produce something that runs wrong.
================================================================================
*/}}

{{/* helm.sh/chart label value. */}}
{{- define "sdp.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
The object name for one service. Takes a dict of (root, name).

Deliberately NOT prefixed with the release name by default -- see naming.prefix in
values.yaml. The Prometheus Operator derives the `job` label from the Service name,
and every panel in infrastructure/grafana/dashboards/platform-overview.json
aggregates by (job), so the Service names are part of this platform's
observability contract rather than a cosmetic choice.
*/}}
{{- define "sdp.name" -}}
{{- printf "%s%s" (default "" .root.Values.naming.prefix) .name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
The object name for a chart-owned object that is NOT a workload -- the ConfigMaps,
and the singleton ServiceMonitor/PrometheusRule/Ingress/NetworkPolicy objects.
Takes a dict of (root, name), where `name` is the BARE name with no "sdp-" on it.

These get "sdp-" when naming.prefix is empty, so the defaults match the names the
platform's own documentation uses (sdp-common, sdp-user-service) -- and they get
naming.prefix INSTEAD when one is set, rather than on top of it, so a prefixed
release reads `prod-common` and not `prod-sdp-common`.

Workload names deliberately do NOT go through this: a Service must be named exactly
`order-service`, because the Prometheus Operator derives the `job` label from it.
Secret names do not either -- those are literal references to objects this chart
does not own, so what an operator writes in values is what appears in the manifest.
*/}}
{{- define "sdp.configName" -}}
{{- printf "%s%s" (.root.Values.naming.prefix | default "sdp-") .name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Labels on every object. Takes (root, name).

app.kubernetes.io/part-of is load-bearing rather than decorative: it is what the
ServiceMonitor and the NetworkPolicy select on.
*/}}
{{- define "sdp.labels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/version: {{ include "sdp.imageTag" (dict "root" .root "svc" (default (dict) .svc)) | trunc 63 | trimSuffix "-" | quote }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/part-of: smart-delivery-platform
helm.sh/chart: {{ include "sdp.chart" .root }}
{{- end -}}

{{/*
Selector labels. Takes (root, name).

A Deployment's selector is IMMUTABLE, so this set must never gain or lose a key
in a way that changes existing releases -- an edit here is an uninstall and
reinstall, not an upgrade. It is kept to three stable keys for that reason, and
excludes app.kubernetes.io/version (which changes on every image bump) for the
obvious one.
*/}}
{{- define "sdp.selectorLabels" -}}
app: {{ include "sdp.name" (dict "root" .root "name" .name) }}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{/*
The opt-in scrape label, on both the Service and the pod template.

An explicit label rather than relying on part-of alone, so a future workload in
this namespace that has no /actuator -- the React frontend, a one-off Job -- is
not scraped into a permanently-down target. The frontend Service deliberately
does NOT carry it.
*/}}
{{- define "sdp.metricsLabels" -}}
sdp.metrics/scrape: "true"
{{- end -}}

{{/* Resolved image tag for one service. Takes (root, svc). */}}
{{- define "sdp.imageTag" -}}
{{- $svc := default (dict) .svc -}}
{{- $img := default (dict) $svc.image -}}
{{- $tag := $img.tag | default .root.Values.image.tag | default .root.Chart.AppVersion -}}
{{- $tag -}}
{{- end -}}

{{/* Fully qualified image reference for one service. Takes (root, name, svc). */}}
{{- define "sdp.image" -}}
{{- $svc := default (dict) .svc -}}
{{- $img := default (dict) $svc.image -}}
{{- $repoName := $img.name | default .name -}}
{{- printf "%s/%s/%s:%s" .root.Values.image.registry .root.Values.image.repository $repoName (include "sdp.imageTag" (dict "root" .root "svc" $svc)) -}}
{{- end -}}

{{/*
The in-cluster URL of one service. Takes (root, name, port).

The FQDN, not a bare Service name: a bare name resolves only from inside the same
namespace, and these values are also what the OTLP endpoint and any
cross-namespace caller would need. Writing them the same way everywhere means
there is one form to get right.
*/}}
{{- define "sdp.serviceUrl" -}}
{{- printf "http://%s.%s.svc.cluster.local:%v" (include "sdp.name" (dict "root" .root "name" .name)) .root.Release.Namespace .port -}}
{{- end -}}

{{/*
The port NAME the probes and the metrics scrape target. Takes (root).

"http" unless a management port is configured, in which case "mgmt". One value
for the whole release -- see the managementPort validation below for why it
cannot be per service.
*/}}
{{- define "sdp.probePortName" -}}
{{- $mgmt := 0 -}}
{{- range $name, $svc := .Values.services -}}
{{- if and $svc.enabled (gt (int (default 0 $svc.managementPort)) 0) -}}
{{- $mgmt = int $svc.managementPort -}}
{{- end -}}
{{- end -}}
{{- if gt $mgmt 0 -}}mgmt{{- else -}}http{{- end -}}
{{- end -}}

{{/*
The list of enabled service names, prefixed -- i.e. the `job` label values
Prometheus will see. Returned space-separated so a caller can `splitList " "`.

Generated rather than hardcoded so the PrometheusRule's job regex cannot drift
from the Services the chart actually creates, and so it follows naming.prefix.
*/}}
{{- define "sdp.jobNames" -}}
{{- $jobs := list -}}
{{- range $name, $svc := .Values.services -}}
{{- if $svc.enabled -}}
{{- $jobs = append $jobs (include "sdp.name" (dict "root" $ "name" $name)) -}}
{{- end -}}
{{- end -}}
{{- join " " $jobs -}}
{{- end -}}

{{/*
================================================================================
sdp.validate -- included from the top of deployment.yaml, renders nothing.

Every check here is a failure that would otherwise be discovered later and cost
more: a platform-wide intermittent 401, a rollout that cannot complete, a pod
stuck in CreateContainerConfigError, or a derived value computed from a number
nothing respects. `helm template` surfaces all of them without a cluster.
================================================================================
*/}}
{{- define "sdp.validate" -}}

{{- /* ---- migrations ------------------------------------------------------- */ -}}
{{- if ne (default "" .Values.migrations.strategy) "startup" -}}
{{- fail (printf "migrations.strategy is %q; the only supported value is \"startup\". A pre-upgrade Job needs a migrate-and-exit mode these images do not have: FLYWAY_ENABLED=false is half of one, but four of the six schema-owning services carry @EnableScheduling, Spring's ThreadPoolTaskScheduler threads are non-daemon, and there is no spring.task.scheduling.enabled property in Boot 3.5 -- so a migrate pod would migrate and then sit there forever, the Job would never complete, and it would fail the helm upgrade at activeDeadlineSeconds. See README.md, \"Migrations\"." .Values.migrations.strategy) -}}
{{- end -}}

{{- /* ---- the JWT signing key, which is the worst failure in this chart ---- */ -}}
{{- $us := index .Values.services "user-service" -}}
{{- if and $us $us.enabled -}}
{{- $usReplicas := int (default 1 $us.replicaCount) -}}
{{- $usAuto := dig "autoscaling" "enabled" false $us -}}
{{- if and (not .Values.secrets.jwtSigningKeyProvisioned) (or (gt $usReplicas 1) $usAuto) -}}
{{- fail "user-service is configured for more than one replica (replicaCount > 1, or autoscaling.enabled) while secrets.jwtSigningKeyProvisioned is false. Do not do this. With no JWT_PRIVATE_KEY, JwtKeyProvider generates a DIFFERENT throwaway RSA key in every replica and publishes them all under the SAME kid. A resource server that cached pod B's key rejects pod A's tokens with an invalid-signature 401 that Spring Security cannot heal -- it refetches a JWK set only on an UNKNOWN kid, and this kid is known, just wrong -- so roughly (N-1)/N of tokens fail per verifier, the failing set reshuffles on every cache refresh, and the 401 is byte-for-byte the one a forged token produces. It does not stop at logins: order-service's SERVICE token fails at inventory-service, a 401 is not in that breaker's ignore-exceptions, and the breaker opens -- so a missing signing key reads on the dashboard as inventory-service being down. Provision one shared key (see NOTES.txt), then set secrets.jwtSigningKeyProvisioned=true." -}}
{{- end -}}
{{- end -}}

{{- range $name, $svc := .Values.services -}}
{{- if $svc.enabled -}}

{{- /* ---- the outbox poll interval is derived from replicaCount ------------ */ -}}
{{- if and $svc.hasOutbox $.Values.outbox.scalePollInterval (dig "autoscaling" "enabled" false $svc) -}}
{{- fail (printf "%s has hasOutbox=true and autoscaling.enabled=true while outbox.scalePollInterval is true. The chart derives OUTBOX_POLL_INTERVAL_MS from replicaCount (interval x replicas keeps the fleet's database load and per-aggregate latency identical to one replica -- ADR 006), and an HPA makes replicaCount a number nothing respects: the pods would poll at a rate computed for a replica count they are not running at. Either set outbox.scalePollInterval=false and choose the interval deliberately, or leave the HPA off." $name) -}}
{{- end -}}

{{- /* ---- the three shutdown numbers are one decision --------------------- */ -}}
{{- $needed := add (int $svc.preStopSleepSeconds) (mul 2 (int $svc.shutdownPhaseTimeoutSeconds)) -}}
{{- if lt (int $svc.terminationGracePeriodSeconds) $needed -}}
{{- fail (printf "services.%s: terminationGracePeriodSeconds is %v but preStopSleepSeconds (%v) + 2 x shutdownPhaseTimeoutSeconds (%v) needs at least %v. spring.lifecycle.timeout-per-shutdown-phase bounds EACH shutdown phase independently, and there are always at least two -- the graceful request drain and the web-server stop are separate phases -- so one phase timeout of drain plus one of headroom for the remaining phases and bean destruction is the floor. Below it the kubelet SIGKILLs a pod that was shutting down correctly, which is how a rolling deploy starts producing duplicate Kafka deliveries and outbox_publish_failures spikes. Raise the grace period, or lower shutdownPhaseTimeoutSeconds (the chart passes it as SHUTDOWN_PHASE_TIMEOUT, so both move together)." $name $svc.terminationGracePeriodSeconds $svc.preStopSleepSeconds $svc.shutdownPhaseTimeoutSeconds $needed) -}}
{{- end -}}

{{- /* ---- a derived key must not also be set by hand ---------------------- */ -}}
{{- if and $svc.hasOutbox (hasKey (default (dict) $svc.config) "OUTBOX_POLL_INTERVAL_MS") -}}
{{- fail (printf "services.%s.config sets OUTBOX_POLL_INTERVAL_MS, which the chart derives from outbox.basePollIntervalMs and replicaCount. Two values for one key in one ConfigMap is a duplicate YAML key; set outbox.scalePollInterval=false and outbox.basePollIntervalMs instead." $name) -}}
{{- end -}}

{{- /* ---- every referenced Secret must exist when the chart creates them --- */ -}}
{{- if $.Values.secrets.create -}}
{{- range $e := default (list) $svc.secretEnv -}}
{{- if not (hasKey (default (dict) $.Values.secrets.placeholders) $e.secretName) -}}
{{- fail (printf "services.%s.secretEnv references Secret %q, but secrets.create is true and secrets.placeholders has no such entry -- the pod would sit in CreateContainerConfigError. Add the placeholder, or set secrets.create=false and create the Secret out of band." $name $e.secretName) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- /* ---- the management port is a platform-wide decision, not per service - */ -}}
{{- if gt (int (default 0 $svc.managementPort)) 0 -}}
{{- range $otherName, $other := $.Values.services -}}
{{- if $other.enabled -}}
{{- if ne (int (default 0 $other.managementPort)) (int $svc.managementPort) -}}
{{- fail (printf "services.%s.managementPort is %v but services.%s.managementPort is %v. This has to be the same for every enabled service: there is ONE ServiceMonitor, and a scrape endpoint selects ONE port name, so a mixed release would scrape the traffic port of the services that moved actuator away from it and every one of those targets would be down. Set it on all eight or none." $name $svc.managementPort $otherName (default 0 $other.managementPort)) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- end -}}
{{- end -}}

{{- /* ---- Ingress ---------------------------------------------------------- */ -}}
{{- if .Values.ingress.enabled -}}
{{- if not .Values.ingress.host -}}
{{- fail "ingress.enabled is true but ingress.host is empty. A host-less Ingress rule would match every request the controller receives, including other applications' -- and the gateway is the wrong default backend for a cluster." -}}
{{- end -}}
{{- $gw := index .Values.services "api-gateway" -}}
{{- if not (and $gw $gw.enabled) -}}
{{- fail "ingress.enabled is true but services.api-gateway.enabled is false; the Ingress would route /api/v1 at a Service that does not exist." -}}
{{- end -}}
{{- end -}}

{{- end -}}
