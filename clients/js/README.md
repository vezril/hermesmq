# @hermesmq/client (JavaScript)

Typed, **zero-dependency** JavaScript/ESM client for the
[HermesMQ](https://github.com/vezril/hermesmq) REST API — topics, publish (with TTL / idempotency /
producer identity), subscriptions, pull/ack, and health. Uses the global `fetch` (Node ≥ 18 or any
browser); TypeScript types ship in `index.d.ts`.

## Install

Two options (npm can't install a bare repo subdirectory):

```bash
# 1. Vendor it — the client is a single dependency-free file + types:
curl -O https://raw.githubusercontent.com/vezril/hermesmq/main/clients/js/index.js \
     -O https://raw.githubusercontent.com/vezril/hermesmq/main/clients/js/index.d.ts

# 2. Or add via a git checkout / workspace file: dependency:
#    "dependencies": { "@hermesmq/client": "file:../hermesmq/clients/js" }
```

## Quick start

```js
import { HermesClient } from "@hermesmq/client"; // or "./vendor/hermesmq/index.js"

const hermes = new HermesClient("http://hermesmq.hermesmq.svc.cluster.local:8080");

await hermes.createTopic("orders.events", { team: "commerce" });
await hermes.createSubscription("orders.fulfillment", "orders.events");

const { messageId, deduplicated } = await hermes.publish(
  "orders.events",
  JSON.stringify({ orderId: 42 }),
  {
    attributes: { source: "checkout" },
    idempotencyKey: "order-42-created", // broker dedups repeats
    producerId: "checkout-1",           // shows up in hermesmq_topic_producers
  }
);

for (const message of await hermes.pull("orders.fulfillment", { max: 10, consumerId: "worker-1" })) {
  handle(message.payload, message.attributes);
  await hermes.ack("orders.fulfillment", [message.ackId]);
}
```

Auth (when the broker has it enabled): `new HermesClient(url, { token })` sends
`Authorization: Bearer`; `{ apiKey }` sends `X-API-Key`. Neither is sent by default.

Errors: any non-2xx rejects with `HermesClientError` (`.status`, `.body`) — except `getTopic` on a
missing topic, which resolves to `null`. Note that a **deleted subscription's id stays reserved**
broker-side (its journal persists), so deleting then re-creating the same name is rejected with 409.

## Develop

```bash
npm install
npm test   # hermetic — vitest against an in-process node:http stub, no broker needed
```
