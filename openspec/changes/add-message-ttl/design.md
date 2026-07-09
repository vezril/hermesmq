## Context

A `Message` is `(id, payload, attributes, publishTime)`, built via a validating `Message.from`. Subscriptions hold outstanding messages in a state machine (AVAILABLE/LEASED, attempt counts); `Lease` returns AVAILABLE messages; the redelivery sweeper reads a durable `outstanding_leases` read model (maintained by a projection over tagged subscription events) and issues `ExpireAckDeadline` for overdue leases, off the hot path. Dead-lettering fires only at `maxDeliveryAttempts`. There is no wall-clock expiry — a never-acked (or never-leased) message lives forever. TTL adds exactly that, reusing the sweeper/read-model pattern the redelivery feature established.

## Goals / Non-Goals

**Goals:**
- A message can carry an `expireTime`; once past it, the message is never delivered and is purged from the subscription (dropped, not redelivered or dead-lettered).
- TTL from a per-publish `ttlSeconds` or a configurable global default; off by default (no `expireTime`).
- Enforcement is pure in the domain (Lease skips expired; `ExpireMessage`/`MessageExpired` purge), discovered from a durable read model, and swept off the hot path.
- Additive schemas (existing journals stay valid); TDD; behavior unchanged when no TTL is set.

**Non-Goals:**
- Per-subscription/per-topic TTL, dead-letter-on-expiry, retroactive expiry of pre-existing messages, or sub-second precision.

## Decisions

- **`Message.expireTime: Option[Instant]`.** Added to the case class and to `Message.from(..., expireTime: Option[Instant] = None)` — the default keeps every existing call site and journal valid. `def expired(now)` = `expireTime.exists(!_.isAfter(now))`.
- **Domain enforcement.** `SubscriptionCommand.ExpireMessage(ackId, now)` → `decide` emits `SubscriptionEvent.MessageExpired(ackId)` when the ack is outstanding **and** its message is expired at `now`, else `Right(Nil)` (no-op — not expired, or gone). `evolve(MessageExpired)` removes it (like dead-letter, no republish). `Lease` filters `availableMessages` to non-expired at its `now`, so an expired message is never leased/returned. TTL is orthogonal to attempt counting: expiry can happen at any state, before `maxDeliveryAttempts`.
- **Publish computes `expireTime`.** REST `PublishRequest` and gRPC `PublishRequest` gain optional `ttlSeconds`. The publish path derives the effective TTL (`ttlSeconds` if > 0, else the global default if > 0, else none) and passes `expireTime = publishTime + ttl` to `Message.from`. No TTL → `None` → today's behavior.
- **Serialization.** Extend the explicit JSON `Message` format with an optional `expireTime` (ISO-8601), read tolerantly (absent → `None`). Because every message-carrying event/state reuses this format, `MessageDelivered`/`MessagePublished`/snapshots all round-trip; old journals (no field) deserialize to `None`.
- **Read model + sweeper (mirror redelivery).** A durable `expiring_messages(subscription_id, ack_id, expire_time)` view holds only outstanding messages that *have* an `expireTime`. A projection over the existing all-events subscription tag inserts on `MessageDelivered` (when `expireTime` is set) and deletes on `MessageAcknowledged`/`MessageDeadLettered`/`MessageExpired`. A `TtlSweeper` (structural twin of `RedeliverySweeper`) periodically queries `expire_time <= now` and issues `ExpireMessage(ackId, now)` — idempotent and safe alongside the redelivery sweep.
- **Config.** `TtlConfig(defaultTtl: FiniteDuration)` under `hermesmq.ttl.default` (default `0` = off), fail-fast on a negative value; the TTL sweep reuses the existing sweep interval.

## Risks / Trade-offs

- **Wall-clock across nodes.** `expireTime` is computed from `publishTime` (the publishing node's clock) and compared against the sweeper's `now`. Modest skew just shifts expiry by seconds — acceptable for a coarse TTL; documented.
- **Lazy vs. proactive on the never-touched path.** `Lease` filtering handles the delivery path immediately; the sweeper handles never-leased/never-acked messages within one sweep interval. Between publish+deliver and the next sweep, an expired-but-outstanding message is counted in backlog — bounded by the sweep interval, not unbounded.
- **Only TTL'd messages carry overhead.** The read model holds rows only for messages with an `expireTime`, so TTL-off deployments add no rows and no sweep work.
- **Interaction with redelivery/dead-letter.** A message can be simultaneously overdue-leased and expired; `ExpireMessage` removes it and a racing `ExpireAckDeadline` becomes a no-op (and vice-versa) — both are idempotent purges/redeliveries over the same outstanding map. Expiry always wins by removing the entry.
- **Drop-on-expiry is silent.** Expired messages vanish with no dead-letter trail; if audit is needed, dead-letter-on-expiry is the noted future option.
