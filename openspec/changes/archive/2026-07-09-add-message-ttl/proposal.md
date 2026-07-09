## Why

A subscription's backlog can grow without bound: a slow or dead consumer that never acks holds messages indefinitely (redelivery keeps them alive; dead-lettering only fires at the attempt limit, which a never-leased message never reaches). Many workloads want messages that are worthless after a deadline — a stale price tick, an expired session event — to simply **expire and disappear** rather than pile up or be redelivered forever. Per-message TTL gives producers that control and bounds backlog by wall-clock time, complementing the existing lease/redelivery/dead-letter machinery.

## What Changes

- A message gains an optional **`expireTime`** (`publishTime + TTL`), set from a per-publish `ttlSeconds` or a configurable **global default TTL** (`0` = no expiry). Event/state schemas extend additively; Java serialization stays off.
- The Subscription domain enforces TTL: **`Lease` never returns an expired message**, and a new **`MessageExpired`** event (via an `ExpireMessage` command) purges an outstanding message whose `expireTime` has passed. TTL is wall-clock expiry, **distinct** from ack-deadline redelivery and from max-attempts dead-lettering.
- Expired outstanding messages are discovered from a durable read model (mirroring the outstanding-lease read model) and purged by the **periodic sweeper**, off the hot path.
- Expired messages are **dropped** — not redelivered, not dead-lettered.
- TTL flows through **REST publish and gRPC publish**; behavior is unchanged when no TTL is set (no `expireTime`).

## Capabilities

### New Capabilities
- `message-ttl`: Per-message time-to-live — a message expires at `publishTime + TTL`, is never delivered once expired, and is purged from a subscription's outstanding set by the sweeper; TTL comes from a per-publish override or a configurable global default, and expired messages are dropped.

### Modified Capabilities
- `subscription-lifecycle`: `Lease` skips expired messages, and a new `ExpireMessage`/`MessageExpired` purges an outstanding message past its `expireTime` (removed from outstanding, not redelivered).

## Impact

- **Domain:** add `expireTime: Option[Instant]` to `Message` (validated builder); add `SubscriptionEvent.MessageExpired(ackId)` and `SubscriptionCommand.ExpireMessage(ackId, now)`; `Lease` filters expired; `evolve` removes on expiry. Pure, TDD.
- **Config:** a `hermesmq.ttl.default` (default `0` = off) global TTL; a per-publish `ttlSeconds` overrides it. Fail-fast validation.
- **Publish path:** REST `PublishRequest` and gRPC `PublishRequest` gain optional `ttlSeconds`; `Message.from` computes `expireTime` from ttl (or the default) and `publishTime`.
- **Serialization:** extend the explicit JSON `Message` format (and thus every event/state carrying a message) with the new field, tolerant of its absence (existing journals stay valid).
- **Read model + sweeper:** a durable view of outstanding messages with an `expireTime` (mirroring `outstanding_leases`), maintained by a projection; the existing periodic sweeper additionally issues `ExpireMessage` for entries past `now`.
- **Docs:** README TTL section (per-publish `ttlSeconds`, default config, drop-on-expiry semantics) and a capability row; per-subscription TTL and dead-letter-on-expiry noted as future.
- **Out of scope:** per-subscription/per-topic TTL, dead-letter-on-expiry, and retroactive expiry of already-journaled messages published before this change (they simply have no `expireTime`).
