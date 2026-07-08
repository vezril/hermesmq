## Context

Single-node HermesMQ uses local get-or-spawn registries (`TopicRegistry`/`SubscriptionRegistry`) and one in-process delivery projection. The entity model (one writer per id, recovered from a shared Postgres journal) maps directly onto Pekko Cluster Sharding. Feature 10a clusters the **write/command side**; cluster-complete *delivery fan-out* (a distributed topic→subscriptions index) is deferred to 10b. Single-node behavior must not regress, and persistence ids/journals must stay compatible.

## Goals / Non-Goals

**Goals:**
- Pekko cluster formation (seed nodes, Artery, split-brain resolver).
- Shard `TopicEntity`/`SubscriptionEntity`; route commands via `EntityRef` from any node; one writer per id cluster-wide.
- Delivery projection as a single cluster-wide instance (no duplicates / offset contention).
- Unchanged single-node behavior and journal compatibility; docs for multi-node.

**Non-Goals (10b or later):**
- Distributed/shared topic→subscriptions index → **multi-node delivery completeness** (10b).
- Slice-partitioned projection scaling across nodes (10b).
- Auto-discovery bootstrap (pekko-management/k8s) — static seed nodes for now.
- Rolling-upgrade / migration tooling; TLS for remoting.

## Decisions

**Cluster Sharding for entities.**
`ClusterSharding(system).init(Entity(TopicTypeKey)(ctx => TopicEntity(TopicId.from(ctx.entityId)...)))` and likewise for subscriptions. `TopicService`/`SubscriptionService` become `sharding.entityRefFor(TypeKey, id).ask(...)`. The local registries are removed. *Alternative:* keep registries and add a routing layer — rejected: sharding is the idiomatic, correct cluster-wide single-writer.

**Persistence ids unchanged.**
Build the entity with the existing `PersistenceId.ofUniqueId("Topic|<id>")` from `ctx.entityId`, not the sharding default `PersistenceId(typeKey, entityId)`. Keeps existing journals readable. Entity id = the raw topic/subscription id string. *Alternative:* adopt the sharding-standard persistence id — cleaner long-term but breaks v0.3.0 journals; not worth it now.

**Membership: static `seed-nodes` + Split Brain Resolver.**
Configure `pekko.actor.provider = cluster`, Artery canonical host/port (env-overridable), and `seed-nodes`. A single node lists itself → one-node cluster, unchanged behavior. Enable the built-in SBR downing provider (`org.apache.pekko.cluster.sbr.SplitBrainResolverProvider`) so a partition can't create two owners of an entity. *Alternative:* pekko-management + cluster-bootstrap discovery — better for dynamic/k8s, but heavier; static seeds are simplest and testable now.

**Delivery projection: `ShardedDaemonProcess` of size 1.**
Run the projection under `ShardedDaemonProcess.init(..., numberOfInstances = 1, ...)` so exactly one instance runs cluster-wide and fails over on node loss. This avoids multiple projection instances sharing one `projectionId`/offset row (which would contend/duplicate). Size 1 keeps 10a simple; 10b partitions by slice for scale. *Alternative:* cluster singleton — equivalent for size 1; `ShardedDaemonProcess` is the path that generalizes to 10b.

**The in-memory index stays (known 10a gap).**
The topic→subscriptions index remains per-node in memory. On the single projection node it is complete only for subscriptions created via that node's HTTP; single-node is fully correct, multi-node delivery is incomplete until 10b makes the index distributed. Documented explicitly.

**Config via env for multi-node.**
`HERMESMQ_CLUSTER_HOST`/`_PORT` (Artery bind/canonical) and `HERMESMQ_CLUSTER_SEEDS` (comma-separated) so the same image runs as any node. Default: bind localhost, self-seed.

## Risks / Trade-offs

- **Multi-node delivery is incomplete in 10a** → clearly scoped and documented; single-node unaffected; 10b closes it. Reviewers must not read 10a as "clustered delivery done".
- **Sharding config/persistence-id mistakes could orphan journals** → keep the exact existing persistence-id string; a recovery test asserts an entity built through sharding reads a pre-existing journal.
- **Split-brain / downing misconfiguration** → enable SBR from the start; document that all nodes need consistent `seed-nodes` and reachable remoting ports.
- **Testing clusters is heavy** → unit-test the sharded-entity routing in a **single-node cluster** (ActorTestKit with cluster config): commands via `EntityRef` reach the entity, recovery works. Assert SBR/provider config is present. Full multi-node is validated manually / left to integration.
- **Offset contention if the projection ever runs >1** → `ShardedDaemonProcess(1)` guarantees one instance; asserted by construction + documented.
- **Remoting ports in Docker/compose** → document exposing the Artery port and setting canonical hostname per node.

## Migration Plan

TDD where feasible (sharding logic in a single-node cluster; formation/SBR asserted via config):
1. Add cluster/sharding deps + cluster config (provider, Artery, seed-nodes, SBR); a one-node cluster forms and existing server tests stay green.
2. Init sharding for Topic/Subscription entities (keep persistence ids); implement `TopicSharding`/`SubscriptionSharding`; point `TopicService`/`SubscriptionService` at `EntityRef`; remove the local registries. RED tests (single-node cluster) for cluster-wide routing + recovery → green.
3. Start the delivery projection via `ShardedDaemonProcess(1)`; wire into `Main`.
4. Config env overrides (`HERMESMQ_CLUSTER_*`); a `docker-compose` two-node example.
5. Manual multi-node smoke: two nodes, create/publish on one, entity/commands work cluster-wide.
6. README: cluster config, running N nodes, the 10a delivery caveat.

Rollback: additive config + a routing swap; reverting restores local registries and a single projection. Journals unchanged, so no data migration either way.

## Open Questions

- **Artery transport**: `aeron-udp` vs `tcp`. Assumption: `tcp` (simpler, no Aeron media driver) for now.
- **Entity passivation**: enable idle passivation now or leave entities resident? Assumption: default passivation is fine; not tuned in 10a.
- **Compose example**: ship a 2-node `docker-compose.cluster.yml`? Assumption: yes, a documented example (may need manual verification rather than automated multi-node tests).
