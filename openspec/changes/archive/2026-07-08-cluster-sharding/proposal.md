## Why

HermesMQ runs as a single node: the topic/subscription registries are local get-or-spawn actors, and the delivery projection is one in-process instance. To run multiple instances (for availability and horizontal scaling), entity ownership must be distributed so there is still exactly one writer per id **across the cluster**, and the delivery projection must run as exactly one instance cluster-wide (not one per node, which would contend on the shared offset store). This is **Feature 10a** — the clustered write side; completing cluster-correct *delivery fan-out* (a distributed topic→subscriptions index) is Feature 10b.

## What Changes

- **Form a Pekko cluster.** Add cluster + sharding dependencies and Artery remoting. Membership uses static `seed-nodes` (a single node self-seeds, so single-node behavior is unchanged); a **Split Brain Resolver** downing provider keeps membership safe under partitions.
- **Shard the Topic and Subscription entities** with `ClusterSharding`: each `TopicEntity`/`SubscriptionEntity` is a sharded entity keyed by its id, so the one-writer-per-id guarantee holds cluster-wide. Persistence ids are unchanged (`Topic|<id>`, `Subscription|<id>`), so existing journals stay compatible.
- **Route commands via sharding.** Replace the local `TopicRegistry`/`SubscriptionRegistry` with `ClusterSharding.entityRefFor(...)`; `TopicService`/`SubscriptionService` resolve an `EntityRef` and ask it. Any node's HTTP server can serve any request (location transparency).
- **Run the delivery projection as a single cluster-wide instance** (`ShardedDaemonProcess` of size 1, or a cluster singleton) so exactly one node processes topic events — avoiding duplicate deliveries and offset-store contention.
- **Wire cluster startup** into `Main` (join, init sharding, start the sharded-daemon projection) and document running a multi-node cluster.

Scope note (honest): 10a makes the **write/command side** clustered — topic/subscription CRUD, publish (journaling), and recovery all work across N nodes with correct single-writer semantics, and single-node behavior is unchanged. **Multi-node delivery *completeness* is not yet done**: the topic→subscriptions index is still per-node in memory, so with >1 node a message is only fanned out to subscriptions known to the projection's node. Feature 10b replaces that index with a distributed/shared one to make delivery cluster-complete.

## Capabilities

### New Capabilities
- `clustering`: Pekko cluster formation (seed nodes, remoting, split-brain resolution) and cluster sharding of the Topic/Subscription entities, with the delivery projection running as a single cluster-wide instance.

### Modified Capabilities
- `topic-admin-api`: the "single-writer per topic id" guarantee is now provided by **cluster sharding across the cluster**, not a local registry.
- `message-delivery`: the delivery projection runs as **exactly one instance cluster-wide** (was a single in-process instance on one node).

## Impact

- **New dependencies**: `pekko-cluster-typed`, `pekko-cluster-sharding-typed` (Artery remoting comes with them). No new external infra beyond the already-shared PostgreSQL.
- **Source**: `ClusterSharding.init` for both entities (new `TopicSharding`/`SubscriptionSharding` wiring); `TopicService`/`SubscriptionService` route via `EntityRef`; `TopicRegistry`/`SubscriptionRegistry` are removed (superseded by sharding). Delivery projection started via `ShardedDaemonProcess`. Cluster config in `application.conf`.
- **Config**: `pekko.actor.provider = cluster`, Artery canonical hostname/port (env-overridable, e.g. `HERMESMQ_CLUSTER_*`), `seed-nodes`, and the SBR downing provider.
- **Ops**: running N nodes requires reachable remoting ports and a consistent `seed-nodes` list; documented. The shared Postgres journal already supports multi-node access.
- **Backward compatible**: a single node forms a one-node cluster and behaves exactly as before; persistence ids/journals unchanged.
- **Deferred to 10b**: distributed topic→subscriptions index for cluster-complete delivery; slice-partitioned projection scaling.
