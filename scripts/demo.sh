#!/usr/bin/env bash
# One command per demo step. Run from the project root: ./scripts/demo.sh <step>
# Layout: top-left terminal runs `worker` (foreground), bottom-left runs the steps, Cloud UI on the right.
set -euo pipefail
cd "$(dirname "$0")/.."
export DOCKER_CLI_HINTS=false   # no "What's next: ... Gordon" tip after the worker is killed

WORKER=northwind-worker   # container name, so `kill` can find the foreground worker

starter() { docker compose --progress quiet run --rm -T starter "$@" 2>&1 | grep -v -E '^(SLF4J|\[main\])' || true; }
newid()   { echo "$(date +%H%M%S)"; }
say()     { printf '\n\033[1m== %s\033[0m\n' "$*"; }
worker_up() { [ -n "$(docker ps -q -f "name=^${WORKER}$")" ]; }
need_worker() { worker_up || { echo "No worker running. Start it in the other terminal: ./scripts/demo.sh worker"; exit 1; }; }

case "${1:-help}" in
  up)
    [ -f .env ] || { echo "Missing .env (copy .env.example and fill it in)"; exit 1; }
    # A background worker from the old layout would quietly take tasks; remove it.
    docker rm -f northwind-order-demo-worker-1 >/dev/null 2>&1 || true
    docker compose up -d --build fakes
    ns="$(grep '^TEMPORAL_NAMESPACE=' .env | cut -d= -f2)"
    say "Temporal Cloud UI: https://cloud.temporal.io/namespaces/${ns}/workflows"
    say "Fake services:     http://localhost:8080/state"
    say "Next, in the top terminal: ./scripts/demo.sh worker"
    ;;

  worker)      # foreground worker; a new name every start
    docker rm -f "$WORKER" >/dev/null 2>&1 || true
    set +e
    docker compose --progress quiet run --rm --name "$WORKER" worker
    code=$?
    set -e
    if [ "$code" = 137 ]; then
      say "Worker process killed (SIGKILL). In-flight orders wait in Temporal. Restart: ./scripts/demo.sh worker"
    else
      say "Worker exited (code $code)."
    fi
    ;;

  kill)        # crash the worker: SIGKILL, no clean shutdown
    worker_up || { echo "No worker running."; exit 1; }
    docker kill "$WORKER" >/dev/null
    say "Worker killed (SIGKILL)."
    ;;

  happy)       # step 1: a normal order (run it twice for 1b: the duplicate is rejected)
    need_worker
    id="${2:-$(newid)}"
    say "Order $id: authorize, reserve, ship, capture, confirm"
    starter start "$id"
    echo "Workflow in the Cloud UI: order-$id   |   downstream: $(curl -s localhost:8080/state)"
    ;;

  saga)        # step 2: capture fails at the end; the saga undoes everything, newest first
    need_worker
    id="${2:-$(newid)}"
    say "Order $id: authorize, reserve, label all succeed; then the payment capture is declined"
    starter start "$id" capture-fails
    say "Downstream systems for order $id after the undo:"
    curl -s "localhost:8080/order?id=$id"
    ;;

  shipping)    # step 4: provider creates the label, then times out
    need_worker
    id="${2:-$(newid)}"
    say "Order $id: shipping provider will create a label, then stall past our 5s timeout"
    starter start "$id" slow-shipping
    say "Provider's labels for order $id (expect exactly one):"
    curl -s localhost:8080/shipments | grep "reference=$id:" || echo "(none)"
    echo "In the UI: createShipment attempt 1 timed out, attempt 2 found the existing label."
    ;;

  crash)       # step 3: kill the worker while it is mid-call to inventory
    need_worker
    id="${2:-$(newid)}"
    say "Order $id: submitting; the worker will be killed while it waits on inventory"
    starter submit "$id" cust-42 SKU-SLOW
    sleep 2   # authorize is done, reserveInventory is in flight (inventory stalls 8s)
    docker kill "$WORKER" >/dev/null
    say "Worker killed mid-call. order-$id is still Running in the UI; nothing is lost."
    echo "Restart it in the top terminal: ./scripts/demo.sh worker   (then: ./scripts/demo.sh result $id)"
    ;;

  reset)   # fresh fake-service state: reused order IDs would find old labels and skip the stalls
    docker compose --progress quiet restart fakes; sleep 2; curl -s localhost:8080/state; echo ;;
  status)  starter status "${2:?orderId}" ;;
  result)  starter result "${2:?orderId}" ;;
  state)   curl -s localhost:8080/state ;;
  reject)  # extra: a declined card, completes as REJECTED
    need_worker; starter start "${2:-$(newid)}" declined ;;
  stock)   # extra: out of stock, payment authorization is voided
    need_worker; starter start "${2:-$(newid)}" cust-42 SKU-OUT ;;
  down)
    docker rm -f "$WORKER" northwind-order-demo-worker-1 >/dev/null 2>&1 || true
    docker compose --profile tools down -v ;;
  *)
    echo "usage: $0 up | worker | happy | saga | shipping | crash | kill | reset | reject | stock | status <id> | result <id> | state | down" ;;
esac
