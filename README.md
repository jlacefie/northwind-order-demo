# Northwind order processing on Temporal

A thin demo of one workflow: submit order, authorize payment, reserve inventory, create shipment, capture payment, send confirmation. It runs on Temporal Cloud against fake downstream services and shows the happy path, duplicate-order rejection, saga compensation, a worker crash, and a shipping timeout retried without a second label.

![Demo flow: forward steps, undo steps, and where each demo step fails](docs/demo-flow.png)

The numbers on the diagram match the run of show below. Source: `docs/demo-flow.svg`.

## Layout

```
build.gradle, Dockerfile, docker-compose.yml, scripts/demo.sh
src/main/java/com/northwind/orders/
  Shared.java                task queue, data types, error types
  OrderWorkflow.java         workflow interface and status query
  OrderWorkflowImpl.java     saga, timeouts, search attributes
  OrderActivities.java       activity interface
  OrderActivitiesImpl.java   HTTP calls to the fakes; shipping looks up by reference before creating;
                             prints one line per step and per failure for the worker pane
  TemporalClients.java       Temporal Cloud connection from .env (address, namespace, API key)
  WorkerMain.java            worker entry point (identity worker-xxxx, new per start)
  StarterMain.java           CLI: start / submit / status / result; starts with ID reuse policy REJECT_DUPLICATE
src/main/java/com/northwind/fakes/
  FakeServices.java          fake payment, inventory, shipping, notify (one process, :8080); magic values below
src/test/java/com/northwind/orders/
  OrderWorkflowTest.java         happy path
  OrderFlowsTest.java            out of stock voids payment; shipment failure undoes everything;
                                 declined capture undoes all three steps, newest first
  OrderShippingTimeoutTest.java  label created then timeout: retry finds it, one label (about 7s, real time)
  OrderReplayTest.java           replays real Cloud histories (happy, shipping, crash) against current code
src/test/resources/histories/    the recorded histories (`temporal workflow show -w order-<id> -o json`)
```

Fake-service magic values: customer `declined` (card declined at authorization), sku `SKU-OUT` (out of stock), customer `capture-fails` (capture declined after the label exists), customer `slow-shipping` (label created, then the reply stalls 12s), sku `SKU-SLOW` (inventory stalls 8s on the first attempt). `GET /order?id=<id>` shows one order's payment, inventory and label state.

## Design decisions

| Decision | Why |
|---|---|
| Workflow ID is `order-<orderId>`, reuse policy `REJECT_DUPLICATE` | A second submit of the same order is rejected by Temporal, running or finished, for the namespace's retention period; no dedupe table. (The default policy only rejects while the first run is open.) Beyond retention, the order record in Postgres is the check. |
| Idempotency key is `<orderId>:<step>` | Same key on every retry and after a workflow reset. |
| Authorize first, capture after the shipment exists | Most failures need a void (free, instant), not a refund. |
| Undo step registered before the forward step | A call that timed out may have succeeded; the undo still runs. |
| Declined card, out of stock and declined capture are not retried | They are business outcomes; the saga undoes what was done and the workflow completes as `REJECTED` (`CardDeclined`, `OutOfStock`, `PaymentDeclined`). |
| Confirmation email is outside the saga | A failed email must not void a captured payment. |
| Two search attributes only | Lookup by order ID needs none; attributes are for list filtering. |

## Setup (once)

Requirements: Docker Desktop, JDK 21 (only for `./gradlew test`), and a Temporal Cloud namespace with an API key.

### 1. Temporal Cloud connection (`.env`)

The worker and the starter connect to Temporal Cloud using three environment variables. Put them in `.env` in this folder:

```bash
cp .env.example .env
```

| Variable | Value | Where to find it in the Cloud UI |
|---|---|---|
| `TEMPORAL_ADDRESS` | `<namespace>.<account>.tmprl.cloud:7233` | Namespaces, your namespace: the gRPC endpoint |
| `TEMPORAL_NAMESPACE` | `<namespace>.<account>` | Namespaces: the full namespace name |
| `TEMPORAL_API_KEY` | the API key | Settings, API Keys: create a key with access to the namespace (shown once; copy it then) |

How they are used:
- `docker compose` reads `.env` automatically and passes the three variables to the worker and starter containers. `.env` is gitignored and excluded from the Docker image (`.dockerignore`), so the key never lands in git or in the image.
- `TemporalClients.java` reads them at start-up and connects over TLS with the API key. Nothing is hard-coded; if one is missing, the worker or starter stops with `TEMPORAL_... is not set`.
- The same variables work for the `temporal` CLI, which is handy for checking the namespace from a terminal:

  ```bash
  set -a; . ./.env; set +a; export TEMPORAL_TLS=true
  temporal workflow list --limit 5
  ```

Never put the key on a command line or in a file other than `.env`.

### 2. Search attributes

In the Cloud UI, add two custom search attributes to the namespace, both type Keyword: `OrderStatus` and `CustomerId`. Without them the workflow fails on its first status update.

### 3. Build and test

```bash
./gradlew test
```

Compiles everything and runs the 8 tests. They use Temporal's built-in test environment, so they need no Cloud connection.

## Screen layout

Two terminals on the left, Cloud UI on the right. Both terminals in `northwind-order-demo`.

| Pane | Runs | Shows |
|---|---|---|
| Top-left | `./scripts/demo.sh worker` | The worker, in the foreground. New name (`worker-xxxx`) on every start, one line per step: `9003  ReserveInventory  attempt 1`. |
| Bottom-left | the step commands below | Order results. |
| Right | Cloud UI, Workflows list | Event history of each order. |

## Before the panel (10 minutes ahead)

1. Docker Desktop running.
2. Bottom: `./scripts/demo.sh up` (builds the image, starts the fake services, prints the Cloud UI link).
3. Bottom: `./scripts/demo.sh reset` (empty fake-service state, needed after any rehearsal).
4. Top: `./scripts/demo.sh worker`. Wait for `[worker-xxxx] started, polling task queue 'orders'`.
5. Sign in to the Cloud UI and open the Workflows list.

## Demo run of show (about 6 minutes)

Use order IDs that have never been submitted (see "Order IDs" below). 9001-9004 are unused.

| # | Bottom pane | Time | What the panel sees |
|---|---|---|---|
| 1 | `./scripts/demo.sh happy 9001` | 90s | Top pane prints each step as the worker runs it. Result `CONFIRMED`. In the UI, open `order-9001`: the event history shows each activity and its result. |
| 1b (optional) | `./scripts/demo.sh happy 9001` again | 15s | `rejected: order-9001 was already submitted`. Workflow ID Reuse Policy = `REJECT_DUPLICATE`: a double-click or client retry cannot place the order twice. |
| 2 | `./scripts/demo.sh saga 9002` | 90s | Authorize, reserve and label succeed, then the gateway declines the capture. Top pane: `CapturePayment attempt 1: FAILED (PaymentDeclined, not retried ...)`, then `CancelShipment`, `ReleaseInventory`, `VoidAuthorization`: undo, newest first. Result `REJECTED (PaymentDeclined)`. Bottom pane: payment authorized then voided, inventory not reserved, label CANCELLED. In the UI, `OrderStatus` went through `COMPENSATING`. |
| 3 | `./scripts/demo.sh crash 9003` | 2 min | Top pane: `ReserveInventory attempt 1`, then the worker dies (SIGKILL). UI: `order-9003` still Running, ReserveInventory pending at attempt 2, last failure "StartToClose timeout". Top pane: ↑ Enter restarts the worker; a new `worker-xxxx` picks up at `ReserveInventory attempt 2` and finishes. Event history: old worker name before the crash, new one after. Bottom: `./scripts/demo.sh result 9003` prints `CONFIRMED`. |
| 4 (when asked about the shipping timeout) | `./scripts/demo.sh shipping 9004` | 2 min | Provider creates the label, then stalls past our 5s timeout. Top pane: `CreateShipment attempt 1`, then `attempt 2`, which finds the label by reference. Bottom pane: exactly one label for 9004. About 12s later the top pane shows the provider's late reply to attempt 1, discarded by Temporal. |

Optional: support view (filter the Workflows list by `OrderStatus` or `CustomerId`), and the Cloud operator view (namespace metrics, audit log).

After the panel: `./scripts/demo.sh down`.

## Notes

- **Order IDs** cannot be reused, even after `reset`: Temporal rejects a second `order-<id>` for the namespace's retention period. Rehearse with different IDs from the live run, or leave the ID off for a time-of-day ID. Already used on Cloud: 1001, 2001-2006, 3001, 7001-7003, 70031, 700233, 8001-8005, cloud-h1/s1/s2/c1 (and anything you rehearsed with).
- **Why `crash` kills the worker itself:** the activity timeout is 5s, so after about 6s Temporal retries on any live worker and the window for a manual kill is gone. `./scripts/demo.sh kill` kills the worker on its own if you need it.
- **Do not use the Workers page as proof** in step 3: a killed worker stays RUNNING there for about 5 minutes (until its heartbeat expires), then drops off the list. Use the event history worker names.
- **Other commands:** `status <id>` (live query, needs a worker), `state` (fake-service counters), `reject` (card declined at authorization, nothing to undo), `stock` (out of stock: inventory released, payment voided).
