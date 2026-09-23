#!/usr/bin/env bash
#
# End-to-end smoke test for the whole platform (Phase 18).
#
# Brings the Compose stack up, waits for every service to report healthy, and then drives
# a real customer journey **through the gateway only** -- no direct calls to a backend
# service, because "it works when you talk to the service directly" is exactly the claim
# this is meant to stop anyone making. Everything below goes to http://localhost:8080.
#
# What it proves that the test suite cannot: that the eight services actually start
# together, find each other, share a broker, and carry an order end to end across process
# boundaries. Testcontainers proves each service works against real infrastructure; this
# proves the *system* does.
#
# Usage:
#   scripts/e2e-smoke.sh              # build, run, and tear down
#   KEEP_STACK=1 scripts/e2e-smoke.sh # leave the stack up afterwards for poking at
#   SKIP_BUILD=1 scripts/e2e-smoke.sh # reuse whatever is already built
#
# Exits non-zero on the first failed assertion, after dumping `docker compose logs` to
# $LOG_DIR (CI uploads that directory as an artifact).
set -Eeuo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
# The frontend's nginx (docker-compose.yml `web`), the browser's single origin.
WEB="${WEB_BASE_URL:-http://localhost:8088}"
LOG_DIR="${LOG_DIR:-target/e2e-logs}"
ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-admin@smart-delivery.local}"
ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-local-dev-only-admin-password}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-360}"
SAGA_TIMEOUT_SECONDS="${SAGA_TIMEOUT_SECONDS:-90}"

cd "$(dirname "$0")/.."

# --- output helpers ----------------------------------------------------------------

step()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info()  { printf '    %s\n' "$*"; }
pass()  { printf '    \033[0;32mPASS\033[0m %s\n' "$*"; }
fail()  { printf '    \033[0;31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

# An EXIT trap, not ERR: fail() ends the script with an explicit `exit 1`, and bash does
# not run an ERR trap for that -- so with ERR, every assertion failure (which is every
# interesting failure) skipped the log dump AND the teardown. EXIT runs on every path.
on_exit() {
    local exit_code=$?
    trap - EXIT
    if [ "$exit_code" -ne 0 ]; then
        printf '\n\033[0;31mSmoke test failed (exit %s). Dumping logs to %s\033[0m\n' "$exit_code" "$LOG_DIR" >&2
        mkdir -p "$LOG_DIR"
        docker compose logs --no-color --timestamps > "$LOG_DIR/docker-compose.log" 2>&1 || true
        docker compose ps > "$LOG_DIR/docker-compose-ps.txt" 2>&1 || true
        print_unhealthy_diagnostics
    fi
    teardown
    exit "$exit_code"
}

# The full logs go to $LOG_DIR as an artifact, but an artifact is one download away from
# the failure and not everyone reading the job can fetch it. So for every container that
# is not running-and-healthy, the job output itself gets the reason Docker recorded (exit
# code, OOM kill, the last health-check results) and the tail of its log -- which is the
# part that names the actual error.
print_unhealthy_diagnostics() {
    local id name state
    for id in $(docker compose ps -a -q 2>/dev/null); do
        state="$(docker inspect -f '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$id" 2>/dev/null || true)"
        case "$state" in running/healthy|running/) continue ;; esac
        name="$(docker inspect -f '{{.Name}}' "$id" 2>/dev/null | tr -d /)"
        printf '\n\033[0;31m--- %s: %s ---\033[0m\n' "$name" "$state" >&2
        docker inspect -f 'exit={{.State.ExitCode}} oomKilled={{.State.OOMKilled}} restarts={{.RestartCount}} error={{.State.Error}}' "$id" >&2 || true
        docker inspect -f '{{if .State.Health}}{{range .State.Health.Log}}health: exit={{.ExitCode}} {{.Output}}{{end}}{{end}}' "$id" 2>/dev/null | tail -n 3 >&2 || true
        docker logs --tail 60 "$id" >&2 2>&1 || true
    done
}

teardown() {
    if [ "${KEEP_STACK:-0}" = "1" ]; then
        info "KEEP_STACK=1, leaving the stack running"
    else
        docker compose down --volumes --remove-orphans >/dev/null 2>&1 || true
    fi
}

trap on_exit EXIT

# --- HTTP helpers ------------------------------------------------------------------

# api <METHOD> <PATH> [BODY] [BEARER] [EXTRA_HEADER]
# Writes the response body to $BODY_FILE and echoes the HTTP status, so a caller can
# assert on the status without losing the body it needs to read fields out of.
BODY_FILE="$(mktemp)"
api() {
    local method="$1" path="$2" body="${3:-}" token="${4:-}" extra="${5:-}"
    local args=(-sS -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$GATEWAY$path")
    [ -n "$body" ]  && args+=(-H 'Content-Type: application/json' -d "$body")
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    [ -n "$extra" ] && args+=(-H "$extra")
    curl "${args[@]}"
}

# Reads one field out of the last response body. jq is the right tool and CI has it; this
# keeps the script's only hard dependencies to curl and jq.
field() { jq -r "$1" < "$BODY_FILE"; }

expect_status() {
    local expected="$1" actual="$2" what="$3"
    [ "$actual" = "$expected" ] || fail "$what: expected HTTP $expected, got $actual -- $(cat "$BODY_FILE")"
}

require() { command -v "$1" >/dev/null 2>&1 || fail "$1 is required but not installed"; }

# --- bring the stack up ------------------------------------------------------------

require curl
require jq

step "Starting the stack"
if [ "${SKIP_BUILD:-0}" = "1" ]; then
    docker compose up -d
else
    docker compose up -d --build
fi

step "Waiting for every service to report healthy (up to ${HEALTH_TIMEOUT_SECONDS}s)"
deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SECONDS ))
while :; do
    # Every container with a health check must be `healthy`, not just the gateway: `web`
    # depends on the gateway, so it is still `starting` for a few seconds after the
    # gateway first answers. Checking once at that moment failed the run on a container
    # that was about to be fine. `starting` means keep waiting; `unhealthy` means Docker
    # has already given up on it, so there is nothing to wait for.
    not_ready="$(docker compose ps --format '{{.Service}} {{.Health}}' | awk '$2 != "" && $2 != "healthy"' || true)"
    if [ -z "$not_ready" ] && [ "$(api GET /actuator/health)" = "200" ]; then
        break
    fi
    if printf '%s\n' "$not_ready" | grep -q ' unhealthy$'; then
        fail "some services are unhealthy: $(printf '%s' "$not_ready" | tr '\n' ' ')"
    fi
    [ "$(date +%s)" -lt "$deadline" ] \
        || fail "services did not become healthy within ${HEALTH_TIMEOUT_SECONDS}s: $(printf '%s' "$not_ready" | tr '\n' ' ')"
    sleep 5
done
pass "all services healthy, gateway answering on $GATEWAY"

# --- the web tier ------------------------------------------------------------------
# Three things only the production nginx can get wrong, and each one works in `npm run dev`
# regardless -- which is exactly why they are asserted here (docs/frontend.md).

step "Checking the web tier on $WEB"
deadline=$(( $(date +%s) + 60 ))
until [ "$(curl -s -o /dev/null -w '%{http_code}' "$WEB/index.html")" = "200" ]; do
    [ "$(date +%s)" -lt "$deadline" ] || fail "the web tier did not answer on $WEB within 60s"
    sleep 2
done

# 1. The SPA shell, with the security headers on it.
headers="$(curl -sS -D - -o "$BODY_FILE" "$WEB/")"
grep -q '<div id="root">' "$BODY_FILE" || fail "GET $WEB/ did not return the SPA shell"
echo "$headers" | grep -qi "^content-security-policy:.*frame-ancestors 'none'" \
    || fail "GET $WEB/ is missing the Content-Security-Policy header"

# 2. The history fallback: a deep link has no file, and must still get index.html.
status=$(curl -sS -o "$BODY_FILE" -w '%{http_code}' "$WEB/orders/00000000-0000-0000-0000-000000000000")
expect_status 200 "$status" "deep link through the history fallback"
grep -q '<div id="root">' "$BODY_FILE" || fail "a deep link did not fall back to index.html"

# 3. Same-origin API routing: the browser calls /api on the web origin, never :8080.
status=$(curl -sS -o "$BODY_FILE" -w '%{http_code}' "$WEB/api/v1/products?size=1")
expect_status 200 "$status" "GET /api/v1/products through the web origin"
[ "$(field '.content | type')" = "array" ] || fail "the web origin did not proxy /api to the gateway"
pass "web tier: shell + CSP, history fallback, and /api proxied same-origin"

# --- identities --------------------------------------------------------------------

step "Registering a customer and logging in as both customer and admin"
CUSTOMER_EMAIL="smoke-$(date +%s)-$RANDOM@example.com"
CUSTOMER_PASSWORD="smoke-test-password-123"

status=$(api POST /api/v1/users "$(jq -nc --arg e "$CUSTOMER_EMAIL" --arg p "$CUSTOMER_PASSWORD" \
    '{email:$e, password:$p, firstName:"Smoke", lastName:"Test", phoneNumber:"555-0100"}')")
expect_status 201 "$status" "register customer"
CUSTOMER_ID="$(field .id)"

status=$(api POST /api/v1/auth/login "$(jq -nc --arg e "$CUSTOMER_EMAIL" --arg p "$CUSTOMER_PASSWORD" \
    '{email:$e, password:$p}')")
expect_status 200 "$status" "customer login"
CUSTOMER_TOKEN="$(field .accessToken)"

# The admin comes from BootstrapAdminInitializer, which user-service runs at startup when
# BOOTSTRAP_ADMIN_EMAIL/PASSWORD are set -- docker-compose.yml sets them. Registration
# only ever grants CUSTOMER, so there is deliberately no API route to an admin account.
status=$(api POST /api/v1/auth/login "$(jq -nc --arg e "$ADMIN_EMAIL" --arg p "$ADMIN_PASSWORD" \
    '{email:$e, password:$p}')")
expect_status 200 "$status" "admin login (is BOOTSTRAP_ADMIN_* set on user-service?)"
ADMIN_TOKEN="$(field .accessToken)"
pass "customer $CUSTOMER_ID and admin $ADMIN_EMAIL authenticated"

step "Verifying the JWKS is reachable through the gateway and holds no private key"
status=$(api GET /.well-known/jwks.json)
expect_status 200 "$status" "JWKS"
[ "$(field '.keys[0].kty')" = "RSA" ] || fail "JWKS does not publish an RSA key"
[ "$(field '.keys[0] | has("d")')" = "false" ] || fail "JWKS is publishing private key material"
pass "JWKS published, public parameters only"

# --- catalogue and stock -----------------------------------------------------------

step "Creating a shipping address, category, product, warehouse and stock"
# Every name is suffixed with a run id. Category names, product SKUs and warehouse names
# are unique per service, so fixed names would make this script pass exactly once and then
# fail forever -- including against a stack somebody left running from the last run.
RUN_ID="$(date +%s)-$RANDOM"

status=$(api POST "/api/v1/users/$CUSTOMER_ID/addresses" \
    '{"label":"Home","street":"1 Smoke Lane","city":"Testville","state":"TS","postalCode":"00001","country":"USA","isDefault":true}' \
    "$CUSTOMER_TOKEN")
expect_status 201 "$status" "create address"
ADDRESS_ID="$(field .id)"

status=$(api POST /api/v1/categories "$(jq -nc --arg n "Smoke Widgets $RUN_ID" \
    '{name:$n, description:"Created by the smoke test"}')" "$ADMIN_TOKEN")
expect_status 201 "$status" "create category"
CATEGORY_ID="$(field .id)"

SKU="SMOKE-$RUN_ID"
status=$(api POST /api/v1/products "$(jq -nc --arg sku "$SKU" --arg cat "$CATEGORY_ID" \
    '{sku:$sku, name:"Smoke Widget", description:"Created by the smoke test", price:"25.00", imageUrl:null, active:true, categoryId:$cat}')" \
    "$ADMIN_TOKEN")
expect_status 201 "$status" "create product"
PRODUCT_ID="$(field .id)"

status=$(api POST /api/v1/warehouses "$(jq -nc --arg n "Smoke Depot $RUN_ID" \
    '{name:$n, location:"1 Dock Road"}')" "$ADMIN_TOKEN")
expect_status 201 "$status" "create warehouse"
WAREHOUSE_ID="$(field .id)"

STOCK=20
status=$(api POST /api/v1/inventory "$(jq -nc --arg p "$PRODUCT_ID" --arg w "$WAREHOUSE_ID" --argjson q "$STOCK" \
    '{productId:$p, warehouseId:$w, availableQuantity:$q}')" "$ADMIN_TOKEN")
expect_status 201 "$status" "stock the product"
pass "product $SKU stocked with $STOCK units in warehouse $WAREHOUSE_ID"

# --- helpers over the order lifecycle -----------------------------------------------

place_order() {
    local quantity="$1" idempotency_key="$2"
    api POST /api/v1/orders \
        "$(jq -nc --arg a "$ADDRESS_ID" --arg p "$PRODUCT_ID" --argjson q "$quantity" \
            '{shippingAddressId:$a, items:[{productId:$p, quantity:$q}]}')" \
        "$CUSTOMER_TOKEN" "Idempotency-Key: $idempotency_key"
}

order_status() {
    api GET "/api/v1/orders/$1/status" '' "$CUSTOMER_TOKEN" >/dev/null
    field .status
}

# await_status <orderId> <space-separated acceptable statuses>
await_status() {
    local order_id="$1" wanted="$2" current
    local deadline=$(( $(date +%s) + SAGA_TIMEOUT_SECONDS ))
    while :; do
        current="$(order_status "$order_id")"
        for candidate in $wanted; do
            if [ "$current" = "$candidate" ]; then
                echo "$current"
                return 0
            fi
        done
        [ "$(date +%s)" -lt "$deadline" ] \
            || fail "order $order_id stuck in $current after ${SAGA_TIMEOUT_SECONDS}s (wanted one of: $wanted)"
        sleep 2
    done
}

available_stock() {
    api GET "/api/v1/inventory/$PRODUCT_ID" >/dev/null
    field .totalAvailable
}

reserved_stock() {
    api GET "/api/v1/inventory/$PRODUCT_ID" >/dev/null
    field .totalReserved
}

# --- the happy path ----------------------------------------------------------------

step "Placing an order that can be fulfilled, and following it to payment"
IDEMPOTENCY_KEY="smoke-happy-$RUN_ID"
status=$(place_order 2 "$IDEMPOTENCY_KEY")
expect_status 201 "$status" "place order"
HAPPY_ORDER_ID="$(field .id)"
info "order $HAPPY_ORDER_ID placed, polling..."

reached="$(await_status "$HAPPY_ORDER_ID" "PAID SHIPMENT_CREATED OUT_FOR_DELIVERY DELIVERED")"
pass "order reached $reached -- inventory reserved, payment taken, shipment created"

step "Replaying the same Idempotency-Key returns the same order rather than a second one"
status=$(place_order 2 "$IDEMPOTENCY_KEY")
expect_status 201 "$status" "replay order"
[ "$(field .id)" = "$HAPPY_ORDER_ID" ] || fail "replay created a different order: $(field .id)"
pass "replay returned order $HAPPY_ORDER_ID"

# --- the failure path --------------------------------------------------------------

step "Placing an order that exceeds available stock, and checking nothing is left reserved"
before_available="$(available_stock)"
info "available before: $before_available"
status=$(place_order $(( before_available + 10 )) "smoke-oversell-$RUN_ID")
expect_status 201 "$status" "place oversized order"
OVERSELL_ORDER_ID="$(field .id)"

reached="$(await_status "$OVERSELL_ORDER_ID" "FAILED CANCELLED")"
pass "oversized order ended as $reached rather than silently succeeding"

# The saga releases whatever it managed to reserve before the failing line. Availability
# going back to where it started is the assertion that matters: a leaked reservation is
# stock nobody can sell and nothing would ever free.
deadline=$(( $(date +%s) + 30 ))
while [ "$(available_stock)" != "$before_available" ] && [ "$(date +%s)" -lt "$deadline" ]; do sleep 2; done
[ "$(available_stock)" = "$before_available" ] \
    || fail "stock not restored after the failed order: $(available_stock) available, expected $before_available"
[ "$(reserved_stock)" = "0" ] || fail "reservations leaked: $(reserved_stock) still reserved"
pass "availability back to $before_available with nothing reserved"

# --- cancellation and refund ---------------------------------------------------------

step "Cancelling a paid order and checking the payment is refunded"

# Cancelling is only legal up to PAID: once delivery-service has created a shipment the
# order has to go through the delivery workflow instead (docs/order-flow.md). Shipment
# creation follows payment automatically and within a couple of seconds, so this polls
# tightly for PAID and cancels immediately -- and if the shipment still wins the race, it
# tries again with a fresh order rather than failing on a timing accident.
try_cancel_paid_order() {
    local order_id current cancel_status
    api POST /api/v1/orders \
        "$(jq -nc --arg a "$ADDRESS_ID" --arg p "$PRODUCT_ID" '{shippingAddressId:$a, items:[{productId:$p, quantity:1}]}')" \
        "$CUSTOMER_TOKEN" "Idempotency-Key: smoke-cancel-$RUN_ID-$1" >/dev/null
    order_id="$(field .id)"

    local deadline=$(( $(date +%s) + SAGA_TIMEOUT_SECONDS ))
    while :; do
        current="$(order_status "$order_id")"
        [ "$current" = "PAID" ] && break
        case "$current" in
            SHIPMENT_CREATED|OUT_FOR_DELIVERY|DELIVERED)
                info "order $order_id shipped before it could be cancelled; retrying"
                return 1 ;;
            FAILED|CANCELLED)
                fail "order $order_id ended as $current before it could be cancelled" ;;
        esac
        [ "$(date +%s)" -lt "$deadline" ] || fail "order $order_id never reached PAID"
        sleep 0.25
    done

    cancel_status=$(api POST "/api/v1/orders/$order_id/cancel" '' "$CUSTOMER_TOKEN")
    if [ "$cancel_status" = "409" ]; then
        info "order $order_id shipped between the poll and the cancel; retrying"
        return 1
    fi
    expect_status 200 "$cancel_status" "cancel order"
    [ "$(field .status)" = "CANCELLED" ] || fail "cancel returned status $(field .status)"
    CANCELLED_ORDER_ID="$order_id"
    return 0
}

CANCELLED_ORDER_ID=""
for attempt in 1 2 3; do
    if try_cancel_paid_order "$attempt"; then break; fi
done
[ -n "$CANCELLED_ORDER_ID" ] || fail "could not cancel an order while it was still PAID after 3 attempts"

# Compensation is asynchronous as of Phase 17 (ADR 008): the cancel response comes back
# before the refund has happened, so this polls rather than asserting immediately.
deadline=$(( $(date +%s) + SAGA_TIMEOUT_SECONDS ))
while :; do
    if [ "$(api GET "/api/v1/payments/order/$CANCELLED_ORDER_ID" '' "$ADMIN_TOKEN")" = "200" ] \
        && [ "$(field .status)" = "REFUNDED" ]; then
        break
    fi
    [ "$(date +%s)" -lt "$deadline" ] \
        || fail "payment for $CANCELLED_ORDER_ID was not refunded within ${SAGA_TIMEOUT_SECONDS}s (last seen: $(field .status))"
    sleep 2
done
pass "order $CANCELLED_ORDER_ID cancelled while PAID, and its payment is REFUNDED"

# --- done ----------------------------------------------------------------------------

step "All smoke checks passed"
# teardown runs from the EXIT trap, on success as on failure.
