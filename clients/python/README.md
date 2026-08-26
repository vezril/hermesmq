# hermesmq-client (Python)

Typed, synchronous Python 3 client for the [HermesMQ](https://github.com/vezril/hermesmq) REST API —
topics, publish (with TTL / idempotency / producer identity), subscriptions, pull/ack, and health.
Only runtime dependency: `httpx`.

## Install

```bash
pip install "hermesmq-client @ git+https://github.com/vezril/hermesmq#subdirectory=clients/python"
# or with uv:
uv add "hermesmq-client @ git+https://github.com/vezril/hermesmq#subdirectory=clients/python"
```

## Quick start

```python
from hermesmq_client import HermesClient

with HermesClient("http://hermesmq.hermesmq.svc.cluster.local:8080") as hermes:
    hermes.create_topic("orders.events", labels={"team": "commerce"})
    hermes.create_subscription("orders.fulfillment", "orders.events")

    result = hermes.publish(
        "orders.events",
        '{"orderId": 42}',
        attributes={"source": "checkout"},
        idempotency_key="order-42-created",   # broker dedups repeats
        producer_id="checkout-1",             # shows up in hermesmq_topic_producers
    )
    print(result.message_id, result.deduplicated)

    for message in hermes.pull("orders.fulfillment", max_messages=10, consumer_id="worker-1"):
        handle(message.payload, message.attributes)
        hermes.ack("orders.fulfillment", [message.ack_id])
```

Auth (when the broker has it enabled): `HermesClient(url, token="...")` sends
`Authorization: Bearer`; `HermesClient(url, api_key="...")` sends `X-API-Key`. Neither is sent by
default.

Errors: any non-2xx raises `HermesClientError` with `.status` and `.body` — except
`get_topic` on a missing topic, which returns `None`. Note that a **deleted subscription's id stays
reserved** broker-side (its journal persists), so deleting then re-creating the same name is
rejected with a 409.

## Develop

```bash
uv sync
uv run pytest      # hermetic — tests run against an in-process stub, no broker needed
uv run ruff check .
uv run mypy hermesmq_client
```
