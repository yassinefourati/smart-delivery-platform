#!/usr/bin/env bash
# =============================================================================
# Validates every Kubernetes artifact in the repository without a cluster
# (Phase 22). CI runs exactly this (the k8s-manifests job); so can anyone locally.
#
#   1. helm lint         both charts, with each of their values files
#   2. render            the app chart (defaults, placeholders, production) and
#                        sdp-data (primary, DR replica, DR promoted, PITR)
#   3. kubeconform       -strict, against the Kubernetes API schemas AND the real
#                        CRD schemas (CloudNativePG, Barman Cloud, cert-manager,
#                        trust-manager, ESO, Kyverno, Argo, Velero, Prometheus
#                        Operator) -- nothing skipped; a typo in a CRD field fails
#   4. guards            each chart refusal fired on purpose, message checked
#   5. alert rules       promtool (Prometheus) and lokitool (Loki) parse them
#   6. Kyverno           the admission policies applied to the production render:
#                        it must pass, and a :latest / foreign-registry render must not
#   7. shellcheck        the operational scripts
#   8. Falco (optional)  the custom rules load against Falco's default ruleset
#                        (needs Docker; RUN_FALCO_CHECK=1)
#
# Tools on PATH: helm, kubeconform, promtool, lokitool, kyverno, yq (v4), shellcheck.
# The CRD schemas are fetched from the datreeio CRDs-catalog, so this needs network.
# =============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

KUBE_VERSION="${KUBE_VERSION:-1.30.0}"
APP=deploy/helm/smart-delivery-platform
DATA=deploy/helm/sdp-data
BUNDLE=deploy/cluster
OUT="${OUT:-target/k8s-validate}"
CATALOG='https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
# A plausible commit sha, so the production render has immutable image references.
SHA_TAG=0123456789abcdef0123456789abcdef01234567

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
pass() { printf '    \033[0;32mPASS\033[0m %s\n' "$*"; }
fail() { printf '    \033[0;31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

rm -rf "$OUT" && mkdir -p "$OUT"

step "helm lint"
helm lint "$APP" >/dev/null
helm lint "$APP" -f "$APP/values-production.yaml" >/dev/null
helm lint "$DATA" >/dev/null
helm lint "$DATA" -f "$DATA/values-dr.yaml" >/dev/null
pass "both charts, all values files"

step "render"
render() { local name="$1"; shift; helm template "$@" --kube-version "$KUBE_VERSION" > "$OUT/$name.yaml"; pass "$name ($(grep -c '^kind:' "$OUT/$name.yaml") objects)"; }
render app-default       sdp "$APP" -n sdp
# secrets.create renders the placeholder Secrets: the shape of what production needs.
render app-placeholders  sdp "$APP" -n sdp --set secrets.create=true --set frontend.enabled=true
# NOT with secrets.create: production uses ExternalSecrets, and the chart refuses both.
render app-production    sdp "$APP" -n sdp -f "$APP/values-production.yaml" --set frontend.enabled=true --set image.tag="$SHA_TAG"
render data-primary      sdp-data "$DATA" -n sdp-data
render data-replica      sdp-data "$DATA" -n sdp-data -f "$DATA/values-dr.yaml"
render data-promoted     sdp-data "$DATA" -n sdp-data -f "$DATA/values-dr.yaml" --set dr.promoted=true
render data-pitr         sdp-data "$DATA" -n sdp-data -f "$DATA/examples/pitr-order-db.yaml"

step "kubeconform -strict (Kubernetes $KUBE_VERSION + CRD schemas, nothing skipped)"
conform() {
    kubeconform -strict -summary -kubernetes-version "$KUBE_VERSION" \
        -schema-location default -schema-location "$CATALOG" "$@" | tee "$OUT/kubeconform.log" | tail -n 1
    grep -q 'Invalid: 0, Errors: 0, Skipped: 0' "$OUT/kubeconform.log" || fail "kubeconform reported problems (see above)"
}
conform "$OUT"/*.yaml
conform -ignore-filename-pattern 'falco/values\.yaml$' -ignore-filename-pattern '\.(sh|md)$' "$BUNDLE"
pass "all rendered objects and the cluster bundle"

step "quarantine: every allow NetworkPolicy excludes the quarantine label"
# NetworkPolicies are additive, so the quarantine deny-all only isolates a pod if NO
# allow policy still matches it (docs/runbooks/incident-response.md#contain). A new
# policy that forgets the exclusion silently breaks quarantine; this catches it.
missing="$(yq ea 'select(.kind == "NetworkPolicy" and (((.spec.ingress // []) + (.spec.egress // [])) | length) > 0)
    | select(((.spec.podSelector.matchExpressions // []) | map(select(.key == "security.sdp/quarantine" and .operator == "DoesNotExist")) | length) == 0)
    | .metadata.name' "$OUT/app-production.yaml")"
[ -z "$missing" ] || fail "allow policies that would still match a quarantined pod: $(echo "$missing" | grep -v -- '---' | tr '\n' ' ')"
pass "$(yq ea 'select(.kind == "NetworkPolicy") | .metadata.name' "$OUT/app-production.yaml" | grep -vc -- '---') policies checked"

step "chart guards refuse unsafe values"
expect_refused() {
    local what="$1" expected="$2"; shift 2
    if helm template "$@" --kube-version "$KUBE_VERSION" >/dev/null 2>"$OUT/guard.err"; then
        fail "$what: rendered, but the chart should have refused it"
    fi
    grep -qF -- "$expected" "$OUT/guard.err" || { cat "$OUT/guard.err" >&2; fail "$what: refused, but not with \"$expected\""; }
    pass "$what"
}
expect_refused "grace period shorter than the shutdown budget" "terminationGracePeriodSeconds is 10" \
    sdp "$APP" --set services.order-service.terminationGracePeriodSeconds=10
expect_refused "egress restriction without NetworkPolicy" "networkPolicy.enabled is f" \
    sdp "$APP" --set networkPolicy.restrictEgress=true
expect_refused "ExternalSecrets and placeholder Secrets together" "externalSecrets.enabled and secrets.create are both true" \
    sdp "$APP" -f "$APP/values-production.yaml" --set secrets.create=true
expect_refused "verify-full TLS with a DB_URL that does not verify" "does not contain sslmode=verify" \
    sdp "$APP" --set database.tls.enabled=true
expect_refused "canary on a service that autoscales" "has autoscaling.enabled" \
    sdp "$APP" -f "$APP/values-production.yaml" --set 'rollouts.services={api-gateway}'
expect_refused "a database cluster of two" "Three is the floor" \
    sdp-data "$DATA" --set cluster.instances=2
expect_refused "a superuser password" "enableSuperuserAccess=true" \
    sdp-data "$DATA" --set cluster.enableSuperuserAccess=true
expect_refused "runtime role that owns the schema" "The split is the point" \
    sdp-data "$DATA" --set clusters.order-db.appRole=order_owner
expect_refused "a five-field backup cron" "SIX fields" \
    sdp-data "$DATA" --set 'backup.schedule=0 3 * * *'
expect_refused "a PITR target time without a zone" "must be RFC 3339 with a zone" \
    sdp-data "$DATA" -f "$DATA/examples/pitr-order-db.yaml" --set clusters.order-db-pitr.recoverFrom.targetTime=2026-09-23T14:05:00
expect_refused "a PITR over the damaged cluster itself" "Recover into a NEW cluster" \
    sdp-data "$DATA" -f "$DATA/examples/pitr-order-db.yaml" --set clusters.order-db-pitr.recoverFrom.cluster=order-db-pitr

step "alert rules parse"
for r in app-production data-primary; do
    yq ea '[select(.kind == "PrometheusRule") | .spec.groups[]] | {"groups": .}' "$OUT/$r.yaml" > "$OUT/$r.rules.yaml"
    promtool check rules "$OUT/$r.rules.yaml" >/dev/null || { promtool check rules "$OUT/$r.rules.yaml"; fail "promtool: $r"; }
    pass "promtool: $r ($(grep -c 'alert:' "$OUT/$r.rules.yaml") alerts)"
done
yq '.data."sdp-security.yaml"' "$BUNDLE/observability/loki-rules.yaml" > "$OUT/loki.rules.yaml"
lokitool rules lint "$OUT/loki.rules.yaml" >/dev/null 2>&1 || { lokitool rules lint "$OUT/loki.rules.yaml"; fail "lokitool"; }
pass "lokitool: Loki ruler alerts"

step "Kyverno admission policies against the production render"
cat > "$OUT/kyverno-values.yaml" <<'EOF'
apiVersion: cli.kyverno.io/v1alpha1
kind: Value
metadata:
  name: values
namespaceSelector:
  - name: sdp
    labels:
      sdp.io/workload-policy: enforced
  - name: sdp-data
    labels:
      sdp.io/workload-policy: enforced
EOF
# The signature policy is left out: it needs a signed image in a registry and Rekor.
POLICIES=("$BUNDLE/kyverno/restrict-image-registries.yaml" "$BUNDLE/kyverno/disallow-latest-tag.yaml" "$BUNDLE/kyverno/require-requests-limits.yaml")
kyverno apply "${POLICIES[@]}" --resource "$OUT/app-production.yaml" --values-file "$OUT/kyverno-values.yaml" > "$OUT/kyverno.log" 2>&1 \
    || { cat "$OUT/kyverno.log"; fail "the production render violates an admission policy"; }
pass "production render admitted ($(grep -o 'pass: [0-9]*' "$OUT/kyverno.log"))"
helm template sdp "$APP" -n sdp --kube-version "$KUBE_VERSION" --set image.tag=latest > "$OUT/app-latest.yaml"
if kyverno apply "${POLICIES[@]}" --resource "$OUT/app-latest.yaml" --values-file "$OUT/kyverno-values.yaml" >/dev/null 2>&1; then
    fail "a :latest render was admitted"
fi
pass "a :latest render is refused"
sed 's#ghcr.io/yassinefourati/smart-delivery-platform/#docker.io/someone-else/#' "$OUT/app-production.yaml" > "$OUT/app-foreign.yaml"
if kyverno apply "${POLICIES[@]}" --resource "$OUT/app-foreign.yaml" --values-file "$OUT/kyverno-values.yaml" >/dev/null 2>&1; then
    fail "a foreign-registry render was admitted"
fi
pass "a foreign-registry render is refused"

step "shellcheck"
shellcheck scripts/*.sh "$BUNDLE"/backup-bucket/*.sh
pass "scripts/ and deploy/cluster/backup-bucket/"

if [ "${RUN_FALCO_CHECK:-0}" = "1" ]; then
    step "Falco rules load (falco -V, default ruleset + SDP rules)"
    yq '.customRules."sdp-rules.yaml"' "$BUNDLE/falco/values.yaml" > "$OUT/sdp-falco-rules.yaml"
    docker run --rm --entrypoint /usr/bin/falco \
        -v "$PWD/$OUT/sdp-falco-rules.yaml:/tmp/sdp-rules.yaml:ro" \
        "${FALCO_IMAGE:-falcosecurity/falco:0.45.0}" \
        -V /etc/falco/falco_rules.yaml -V /tmp/sdp-rules.yaml
    pass "Falco accepted the SDP rules"
fi

step "All Kubernetes validation passed"
