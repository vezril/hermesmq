# Tasks: add-message-ttl

TDD throughout — for each behavior write the failing test first (Red), implement to green,
then refactor. Run `sbt test` after each step. Order is dependency-first: message model →
domain expiry → serialization → config → publish surfaces → read model → sweeper →
integration & docs. Behavior stays unchanged when no TTL is set.

## 1. Message model: expireTime

- [x] 1.1 (test) `Message.from` accepts an optional `expireTime`; `expired(now)` is true iff `expireTime <= now`; absent → never expired
- [x] 1.2 (impl) Add `expireTime: Option[Instant]` to `Message` (+ builder param defaulting `None`, `expired` helper); existing call sites unchanged

## 2. Domain: Lease skips expired, ExpireMessage purges

- [x] 2.1 (test) `Lease` does not return an available message whose `expireTime <= now`; non-expired ones lease as before
- [x] 2.2 (test) `ExpireMessage(ackId, now)` on an outstanding expired message emits `MessageExpired` and evolving removes it; not-expired / no-ttl / gone → `Right(Nil)`; independent of attempt count
- [x] 2.3 (impl) Add `SubscriptionEvent.MessageExpired`, `SubscriptionCommand.ExpireMessage`; `decide` (Lease filter + ExpireMessage) and `evolve` (remove)

## 3. Serialization

- [x] 3.1 (test) `Message` JSON round-trips with and without `expireTime`; a legacy message JSON (no field) deserializes to `None`; `MessageExpired` round-trips
- [x] 3.2 (impl) Extend the `Message` JSON format with optional `expireTime` (tolerant read); add `MessageExpired` to the `SubscriptionEvent` format

## 4. Configuration (fail-fast)

- [x] 4.1 (test) Default TTL `0` (off); env override parsed; a negative default fails fast
- [x] 4.2 (impl) `TtlConfig(defaultTtl)` parser + `hermesmq.ttl` block (`HERMESMQ_TTL_DEFAULT`)

## 5. Publish surfaces

- [x] 5.1 (test) REST `PublishRequest` with `ttlSeconds` sets `expireTime = publishTime + ttl`; absent uses the default; neither → no expiry
- [x] 5.2 (test) gRPC `PublishRequest` with `ttlSeconds` sets `expireTime`
- [x] 5.3 (impl) Add optional `ttlSeconds` to REST + gRPC publish; a shared helper computes the effective `expireTime` (per-publish → default → none) passed to `Message.from`

## 6. Read model for expiring messages

- [x] 6.1 (test) A projection effect inserts an outstanding message with an `expireTime` and removes it on ack/dead-letter/expire; messages with no `expireTime` are not tracked
- [x] 6.2 (impl) `ExpiringMessageRepository` (trait + InMemory + Jdbc) + `expiring_messages` table; a projection over the all-events subscription tag maintaining it

## 7. TTL sweeper

- [x] 7.1 (test) `sweepOnce` issues `ExpireMessage` for every read-model entry with `expire_time <= now`; future entries untouched; a message acked between scan and expiry is a no-op
- [x] 7.2 (impl) `TtlSweeper` (structural twin of `RedeliverySweeper`) reading the expiring-messages read model; run it as a single cluster-wide daemon in `Main`

## 8. Integration & docs

- [x] 8.1 (test) End-to-end (testcontainers Postgres): publish with a short ttl, leave it unacked, run expiry, and assert the message is purged (gone from outstanding, not redelivered); a no-ttl message is unaffected
- [x] 8.2 (docs) README TTL section (`ttlSeconds` on publish, `HERMESMQ_TTL_DEFAULT`, drop-on-expiry vs redelivery/dead-letter) and a capability row; note per-subscription TTL + dead-letter-on-expiry as future
- [x] 8.3 (refactor) Final pass; `sbt test` green; `openspec validate add-message-ttl --strict` clean
