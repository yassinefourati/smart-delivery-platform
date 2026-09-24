#!/usr/bin/env bash
#
# Fills a running platform with demo data, so the web app (http://localhost:8088) has
# something to show: categories, products, warehouses and stock, a few customers with
# addresses, and -- optionally -- orders that the saga carries forward on its own.
#
# Everything goes through the gateway, as the smoke test does, so the data is created by
# the same endpoints, validation and events a real client would hit. Nothing is written
# to a database directly: a product row with no stock row, or an order with no outbox
# event, would be demo data that lies about how the platform behaves.
#
# SAFE TO RE-RUN. Every step looks for what it is about to create and skips it if it is
# there -- categories and warehouses by name, products by SKU, customers by email, stock
# by the 409 inventory-service returns for an existing row -- and the demo orders carry a
# FIXED Idempotency-Key per customer, so a second run gets the original orders back
# instead of placing new ones. That last part is the platform's own idempotency doing the
# work (docs/order-flow.md#idempotency).
#
# Usage:
#   scripts/seed-demo-data.sh                        # against http://localhost:8080
#   GATEWAY=http://localhost:8088 scripts/seed-demo-data.sh   # through the web tier
#   SEED_ORDERS=0 scripts/seed-demo-data.sh          # catalog, stock and customers only
#
# Needs: a running platform (docker compose up -d), curl and jq.
#
# What it deliberately does NOT create: delivery agents. Orders stop at "waiting for a
# courier" until an admin grants a user DELIVERY_AGENT (Users page), creates their agent
# profile (Agents page) and assigns the shipment -- see docs/local-development.md.
set -Eeuo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
ADMIN_EMAIL="${BOOTSTRAP_ADMIN_EMAIL:-admin@smart-delivery.local}"
ADMIN_PASSWORD="${BOOTSTRAP_ADMIN_PASSWORD:-local-dev-only-admin-password}"
SEED_ORDERS="${SEED_ORDERS:-1}"
# Every demo customer signs in with this. It is demo data on a local stack, printed at
# the end on purpose; never run this script against anything real.
CUSTOMER_PASSWORD="${DEMO_CUSTOMER_PASSWORD:-demo-password-123}"

# --- output ------------------------------------------------------------------------

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
made() { printf '    \033[0;32m+\033[0m %s\n' "$*"; }
kept() { printf '    \033[0;90m= %s (already there)\033[0m\n' "$*"; }
# For orders: a replay answers exactly like a create (201, same id), so neither is claimed.
note() { printf '    \033[0;36m>\033[0m %s\n' "$*"; }
fail() { printf '    \033[0;31mFAIL\033[0m %s\n' "$*" >&2; exit 1; }

for tool in curl jq; do
    command -v "$tool" >/dev/null 2>&1 || fail "$tool is required but not installed"
done

# --- HTTP --------------------------------------------------------------------------

BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

# api <METHOD> <PATH> [JSON_BODY] [TOKEN] [EXTRA_HEADER] -> echoes the status code; the
# body is left in $BODY_FILE.
api() {
    local method="$1" path="$2" body="${3:-}" token="${4:-}" extra="${5:-}"
    local args=(-sS -o "$BODY_FILE" -w '%{http_code}' -X "$method" "$GATEWAY$path")
    [ -n "$body" ]  && args+=(-H 'Content-Type: application/json' -d "$body")
    [ -n "$token" ] && args+=(-H "Authorization: Bearer $token")
    [ -n "$extra" ] && args+=(-H "$extra")
    curl "${args[@]}"
}
field() { jq -r "$1" < "$BODY_FILE"; }

# expect <status> <what> <allowed status...> -- fails with the server's own problem body.
expect() {
    local actual="$1" what="$2"; shift 2
    for ok in "$@"; do [ "$actual" = "$ok" ] && return 0; done
    fail "$what: HTTP $actual -- $(cat "$BODY_FILE")"
}

login() {
    local status
    status=$(api POST /api/v1/auth/login "$(jq -nc --arg e "$1" --arg p "$2" '{email:$e, password:$p}')")
    expect "$status" "login as $1" 200
    field .accessToken
}

# --- the data ----------------------------------------------------------------------
# category|description
CATEGORIES=(
    "Kitchen|Kettles, cookware and everything for the counter"
    "Coffee & Tea|Beans, leaves and the kit to brew them"
    "Home Office|Desks, lighting and the small things that make a day easier"
    "Outdoors|Bottles, bags and gear for getting out"
    "Personal Care|Everyday essentials"
)

# sku|name|category|price|description
PRODUCTS=(
    "KIT-KETTLE-01|Copper Tea Kettle|Kitchen|39.90|A 1.7 litre stovetop kettle with a whistling spout and a cool-touch handle."
    "KIT-PAN-02|Cast Iron Skillet 26cm|Kitchen|44.50|Pre-seasoned, oven safe, and heavy in the way good pans are."
    "KIT-KNIFE-03|Chef's Knife 20cm|Kitchen|59.00|High-carbon stainless steel with a full tang."
    "KIT-BOARD-04|Oak Chopping Board|Kitchen|27.00|End-grain oak, finished with food-safe oil."
    "KIT-SCALE-05|Digital Kitchen Scale|Kitchen|18.99|Weighs to the gram, up to 5 kg."
    "BRW-BEANS-01|House Espresso Beans 1kg|Coffee & Tea|24.00|A chocolatey medium roast that also works as filter."
    "BRW-GRIND-02|Hand Coffee Grinder|Coffee & Tea|49.00|Ceramic burrs and 40 grind settings."
    "BRW-PRESS-03|French Press 1L|Coffee & Tea|29.90|Borosilicate glass with a stainless steel frame."
    "BRW-SENCHA-04|Loose Leaf Sencha 250g|Coffee & Tea|14.50|A grassy green tea from Shizuoka."
    "BRW-MUGS-05|Stoneware Mugs (Set of 4)|Coffee & Tea|32.00|Hand-glazed, dishwasher safe, 350 ml each."
    "OFF-LAMP-01|LED Desk Lamp|Home Office|45.00|Dimmable, with adjustable colour temperature and a USB-C port."
    "OFF-STAND-02|Aluminium Laptop Stand|Home Office|36.00|Raises the screen to eye level; folds flat."
    "OFF-NOTE-03|Dot Grid Notebook A5|Home Office|12.00|160 pages of 100 gsm paper that fountain pens like."
    "OFF-CHAIR-04|Ergonomic Office Chair|Home Office|229.00|Adjustable lumbar support, armrests and seat depth."
    "OUT-BOTTLE-01|Insulated Water Bottle 750ml|Outdoors|25.00|Keeps drinks cold for 24 hours or hot for 12."
    "OUT-PACK-02|Daypack 22L|Outdoors|64.00|Water-resistant, with a padded laptop sleeve."
    "OUT-TORCH-03|Rechargeable Head Torch|Outdoors|29.00|400 lumens and a red night mode."
    "CAR-TOOTH-01|Bamboo Toothbrush (4 pack)|Personal Care|9.50|Plant-based bristles and compostable handles."
    "CAR-SOAP-02|Olive Oil Soap Bar|Personal Care|6.00|Cold-processed, unscented."
    "CAR-TOWEL-03|Cotton Bath Towel|Personal Care|22.00|600 gsm Turkish cotton."
)

# name|location
WAREHOUSES=(
    "Central Depot|14 Harbour Road, Springfield"
    "North Hub|2 Ridge Way, Shelbyville"
)

# sku|central qty|north qty -- chosen so the storefront shows every stock state:
# plenty, "N left", and out of stock.
STOCK=(
    "KIT-KETTLE-01|40|25"   "KIT-PAN-02|18|12"     "KIT-KNIFE-03|3|0"     "KIT-BOARD-04|30|20"
    "KIT-SCALE-05|50|40"    "BRW-BEANS-01|120|80"  "BRW-GRIND-02|15|10"   "BRW-PRESS-03|25|25"
    "BRW-SENCHA-04|60|40"   "BRW-MUGS-05|2|0"      "OFF-LAMP-01|35|15"    "OFF-STAND-02|20|20"
    "OFF-NOTE-03|200|150"   "OFF-CHAIR-04|6|4"     "OUT-BOTTLE-01|90|60"  "OUT-PACK-02|12|8"
    "OUT-TORCH-03|0|0"      "CAR-TOOTH-01|150|100" "CAR-SOAP-02|80|70"    "CAR-TOWEL-03|40|30"
)

# email|first|last|phone|label|street|city|state|postal|country
CUSTOMERS=(
    "jane.doe@example.com|Jane|Doe|+15550100|Home|22 Baker Street|Springfield|IL|62704|US"
    "sam.lee@example.com|Sam|Lee|+15550101|Home|9 Elm Avenue|Shelbyville|IL|62565|US"
    "maria.garcia@example.com|Maria|Garcia|+15550102|Work|300 Market Square|Springfield|IL|62701|US"
)

# email|sku:qty,sku:qty -- one order per customer.
ORDERS=(
    "jane.doe@example.com|KIT-KETTLE-01:1,BRW-SENCHA-04:2"
    "sam.lee@example.com|BRW-GRIND-02:1,BRW-BEANS-01:1"
    "maria.garcia@example.com|OFF-LAMP-01:1,OFF-NOTE-03:3"
)

# --- go ----------------------------------------------------------------------------

step "Checking the gateway at $GATEWAY"
[ "$(api GET '/api/v1/products?size=1')" = "200" ] \
    || fail "no answer from $GATEWAY -- is the platform up? (docker compose up -d)"
ADMIN_TOKEN="$(login "$ADMIN_EMAIL" "$ADMIN_PASSWORD")"
made "signed in as $ADMIN_EMAIL"

step "Categories"
declare -A CATEGORY_ID
expect "$(api GET /api/v1/categories)" "list categories" 200
while IFS=$'\t' read -r id name; do CATEGORY_ID["$name"]="$id"; done < <(field '.[] | [.id, .name] | @tsv')
for entry in "${CATEGORIES[@]}"; do
    IFS='|' read -r name description <<< "$entry"
    if [ -n "${CATEGORY_ID[$name]:-}" ]; then kept "$name"; continue; fi
    status=$(api POST /api/v1/categories "$(jq -nc --arg n "$name" --arg d "$description" '{name:$n, description:$d}')" "$ADMIN_TOKEN")
    expect "$status" "create category $name" 200 201
    CATEGORY_ID["$name"]="$(field .id)"
    made "$name"
done

step "Products"
declare -A PRODUCT_ID
# One page is enough for a demo catalog; the platform accepts a large size.
expect "$(api GET '/api/v1/products?size=500')" "list products" 200
while IFS=$'\t' read -r id sku; do PRODUCT_ID["$sku"]="$id"; done < <(field '.content[] | [.id, .sku] | @tsv')
for entry in "${PRODUCTS[@]}"; do
    IFS='|' read -r sku name category price description <<< "$entry"
    if [ -n "${PRODUCT_ID[$sku]:-}" ]; then kept "$sku $name"; continue; fi
    # `active` is explicit: the server defaults an omitted value to false (not sold).
    body=$(jq -nc --arg s "$sku" --arg n "$name" --arg d "$description" --argjson p "$price" \
        --arg c "${CATEGORY_ID[$category]}" '{sku:$s, name:$n, description:$d, price:$p, active:true, categoryId:$c}')
    status=$(api POST /api/v1/products "$body" "$ADMIN_TOKEN")
    expect "$status" "create product $sku" 200 201
    PRODUCT_ID["$sku"]="$(field .id)"
    made "$sku $name (\$$price)"
done

step "Warehouses"
declare -A WAREHOUSE_ID
expect "$(api GET /api/v1/warehouses '' "$ADMIN_TOKEN")" "list warehouses" 200
while IFS=$'\t' read -r id name; do WAREHOUSE_ID["$name"]="$id"; done < <(field '.[] | [.id, .name] | @tsv')
for entry in "${WAREHOUSES[@]}"; do
    IFS='|' read -r name location <<< "$entry"
    if [ -n "${WAREHOUSE_ID[$name]:-}" ]; then kept "$name"; continue; fi
    status=$(api POST /api/v1/warehouses "$(jq -nc --arg n "$name" --arg l "$location" '{name:$n, location:$l}')" "$ADMIN_TOKEN")
    expect "$status" "create warehouse $name" 200 201
    WAREHOUSE_ID["$name"]="$(field .id)"
    made "$name"
done

step "Stock"
# POST /api/v1/inventory is create-only and answers 409 for an existing (product,
# warehouse) row -- which on a re-run is exactly the "already there" signal. A zero
# quantity still gets a row, so the product shows "Out of stock" rather than "unknown".
stock_row() {
    local sku="$1" warehouse="$2" qty="$3" status
    status=$(api POST /api/v1/inventory "$(jq -nc --arg p "${PRODUCT_ID[$sku]}" --arg w "${WAREHOUSE_ID[$warehouse]}" \
        --argjson q "$qty" '{productId:$p, warehouseId:$w, availableQuantity:$q}')" "$ADMIN_TOKEN")
    expect "$status" "stock $sku in $warehouse" 200 201 409
    if [ "$status" = "409" ]; then kept "$sku in $warehouse"; else made "$sku in $warehouse: $qty"; fi
}
for entry in "${STOCK[@]}"; do
    IFS='|' read -r sku central north <<< "$entry"
    stock_row "$sku" "Central Depot" "$central"
    stock_row "$sku" "North Hub" "$north"
done

step "Customers"
declare -A CUSTOMER_TOKEN CUSTOMER_ID CUSTOMER_ADDRESS
for entry in "${CUSTOMERS[@]}"; do
    IFS='|' read -r email first last phone label street city state postal country <<< "$entry"
    status=$(api POST /api/v1/users "$(jq -nc --arg e "$email" --arg p "$CUSTOMER_PASSWORD" --arg f "$first" \
        --arg l "$last" --arg ph "$phone" '{email:$e, password:$p, firstName:$f, lastName:$l, phoneNumber:$ph}')")
    expect "$status" "register $email" 200 201 409
    if [ "$status" = "409" ]; then kept "$email"; else made "$email"; fi

    status=$(api POST /api/v1/auth/login "$(jq -nc --arg e "$email" --arg p "$CUSTOMER_PASSWORD" '{email:$e, password:$p}')")
    expect "$status" "login as $email (an existing account with a different password?)" 200
    CUSTOMER_TOKEN["$email"]="$(field .accessToken)"
    CUSTOMER_ID["$email"]="$(field .userId)"
    uid="${CUSTOMER_ID[$email]}"; token="${CUSTOMER_TOKEN[$email]}"

    expect "$(api GET "/api/v1/users/$uid/addresses" '' "$token")" "list addresses of $email" 200
    existing="$(jq -r --arg s "$street" '.[] | select(.street == $s) | .id' < "$BODY_FILE" | head -n1)"
    if [ -n "$existing" ]; then
        CUSTOMER_ADDRESS["$email"]="$existing"; kept "$email address: $street"; continue
    fi
    status=$(api POST "/api/v1/users/$uid/addresses" "$(jq -nc --arg lb "$label" --arg st "$street" --arg c "$city" \
        --arg s "$state" --arg pc "$postal" --arg co "$country" \
        '{label:$lb, street:$st, city:$c, state:$s, postalCode:$pc, country:$co, isDefault:true}')" "$token")
    expect "$status" "add address for $email" 200 201
    CUSTOMER_ADDRESS["$email"]="$(field .id)"
    made "$email address: $street, $city"
done

if [ "$SEED_ORDERS" = "1" ]; then
    step "Orders (the saga takes each one to 'waiting for a courier' on its own)"
    for entry in "${ORDERS[@]}"; do
        IFS='|' read -r email lines <<< "$entry"
        items="$(for pair in ${lines//,/ }; do
            IFS=':' read -r sku qty <<< "$pair"
            jq -nc --arg p "${PRODUCT_ID[$sku]}" --argjson q "$qty" '{productId:$p, quantity:$q}'
        done | jq -sc '.')"
        body="$(jq -nc --arg a "${CUSTOMER_ADDRESS[$email]}" --argjson i "$items" '{shippingAddressId:$a, items:$i}')"
        # A fixed key per customer: a re-run replays the original order (201, same id)
        # instead of placing another. If the seed data above ever changes an order's
        # lines, the server answers 409 -- the key now describes a different order.
        status=$(api POST /api/v1/orders "$body" "${CUSTOMER_TOKEN[$email]}" "Idempotency-Key: seed-demo-order-v1-$email")
        expect "$status" "place order for $email" 201 200
        note "$email: order $(field .id) (\$$(field .totalAmount), $lines) -- new, or replayed from an earlier run"
    done
fi

step "Done"
cat <<EOF
    Web app:    ${WEB_URL:-http://localhost:8088}
    Admin:      $ADMIN_EMAIL / $ADMIN_PASSWORD
    Customers:  jane.doe@example.com, sam.lee@example.com, maria.garcia@example.com
                (password: $CUSTOMER_PASSWORD)
EOF
