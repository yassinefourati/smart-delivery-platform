# Service Boundaries

## Principles

1. **A service owns its data.** No service ever opens a connection to another
   service's database. If Service A needs data owned by Service B, it calls Service
   B's API, or reacts to an event Service B published.
2. **A service's public contract is its API and its published events — nothing else.**
   Internal entities are never serialized directly onto the wire; each service maps
   its JPA entities to DTOs at the boundary.
3. **Ownership is unambiguous.** Every piece of business data has exactly one owning
   service. Other services may cache a read-only copy (e.g. Order Service may denormalize
   a product's name/price onto an `OrderItem` at the time of purchase — see
   [database-design.md](database-design.md)), but the owner is always the system of
   record.

## Service responsibilities

### `user-service`
Owns identity: `User`, `Role`, `Address`. Issues and is the source of truth for
authentication (registration, login support, password hashing). Every other service
trusts a JWT issued by this service rather than calling back into it on every request.

### `product-service`
Owns the catalog: `Product`, `Category`. Serves search/filter/pagination. Caches reads
in Redis; is the only service that invalidates that cache (see [ADR 005](adr/005-redis-caching.md)).

### `inventory-service`
Owns warehouse stock: `Warehouse`, `Inventory`, `InventoryReservation`. Is the only
service allowed to mutate `availableQuantity`/`reservedQuantity`. Order Service asks it
to reserve/release/deduct; it never asks Order Service anything.

### `order-service`
Owns `Order`/`OrderItem` and orchestrates the Saga (see [saga.md](saga.md)). Does not
own inventory or payment state — it reacts to events/responses from those services and
records the order's own state transitions.

### `payment-service`
Owns `Payment`/`PaymentTransaction` and the mock payment provider. Knows nothing about
orders beyond an opaque `orderId` reference and an amount to charge.

### `delivery-service`
Owns `DeliveryAgent`, `Shipment`, `Delivery`. Reacts to order confirmation events to
create a shipment; owns agent assignment.

### `notification-service`
Owns nothing that other services need — it's a pure Kafka consumer that renders and
"sends" (logs, in development) notifications. Has no public REST API for other services
to call.

### `api-gateway`
Owns nothing. Stateless routing layer; the only service a browser/mobile client talks
to directly.

## Communication matrix

| Caller | Callee | Mechanism | Why |
|---|---|---|---|
| api-gateway | all services | REST (proxied) | client entry point |
| order-service | product-service | REST (`GET /products/{id}`) | snapshot name/price/availability into the order at creation time -- needed to even build the order, not part of the saga (see [order-flow.md](order-flow.md)) |
| order-service | inventory-service | REST (reserve/release/deduct) | needs an immediate yes/no before proceeding |
| order-service | payment-service | REST (charge/refund) | needs an immediate result to advance the saga |
| order-service | Kafka (`order.*`) | publish | other services react asynchronously |
| inventory-service | Kafka (`inventory.*`) | publish | order-service (saga) and analytics react |
| payment-service | Kafka (`payment.*`) | publish | order-service (saga) and notification-service react |
| delivery-service | Kafka (`shipment.*`, `delivery.*`) | publish | notification-service reacts |
| notification-service | Kafka (all topics above) | consume only | never calls another service back |

No row reads "Service X → Service Y direct SQL." That row does not exist by design.

## What "independently deployable" means here

- Each service has its own `pom.xml`, its own `Dockerfile`, its own Flyway migration
  history, and its own `application.yml`.
- `mvn -pl order-service -am package` builds only what `order-service` actually depends
  on (today: nothing sibling; -am is future-proofing, not a current requirement).
- A service can be deployed, rolled back, or scaled independently of the others. The
  only thing every service shares is dependency **version** management from the root
  POM (see [architecture.md](architecture.md#build-system)) — never code, never a
  runtime dependency.
