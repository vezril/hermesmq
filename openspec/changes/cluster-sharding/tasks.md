## 1. Cluster dependencies & formation

- [x] 1.1 Add `pekko-cluster-typed` + `pekko-cluster-sharding-typed` to the `server` module, pinned to the Pekko version
- [x] 1.2 Configure clustering in `application.conf`: `pekko.actor.provider = cluster`, Artery TCP (canonical host/port, env-overridable via `HERMESMQ_CLUSTER_HOST`/`_PORT`), `seed-nodes` (default self-seed, override via `HERMESMQ_CLUSTER_SEEDS`), and the split-brain-resolver downing provider
- [x] 1.3 Verify a single node forms a one-node cluster and the existing `server` test suite stays green (no behavior regression)
- [x] 1.4 Assert (config test) that a downing provider (SBR) is configured

## 2. Shard the Topic & Subscription entities (TDD)

- [x] 2.1 RED: `TopicShardingSpec` (ActorTestKit with cluster config, single-node cluster) — init sharding for `TopicEntity`; a command sent via `EntityRef` is handled; a second command for the same id reaches the same entity; confirm red
- [x] 2.2 GREEN: implement `TopicSharding` (`ClusterSharding.init` with an `Entity` that builds `TopicEntity` from `entityId`, keeping the `Topic|<id>` persistence id); pass
- [x] 2.3 GREEN: `SubscriptionSharding` likewise; sharding spec for subscription routing
- [x] 2.4 Point `RegistryTopicService`/`RegistrySubscriptionService` (rename to sharding-backed) at `EntityRef`; remove `TopicRegistry`/`SubscriptionRegistry` and their specs (superseded); update the pub/sub + admin wiring
- [x] 2.5 Edge: a recovery test — an entity built through sharding recovers state from a pre-existing journal (persistence id compatibility); green
- [x] 2.6 REFACTOR: share the sharding-init/EntityRef-ask helper across topic/subscription; re-run green

## 3. Single cluster-wide delivery projection

- [x] 3.1 Start the delivery projection via `ShardedDaemonProcess` with `numberOfInstances = 1`, so exactly one instance runs cluster-wide (fails over on node loss)
- [x] 3.2 Wire cluster join + sharding init + sharded-daemon projection into `Main`; single-node run behaves as before
- [x] 3.3 Verify (test/documented) that only one projection instance/`projectionId` is active, avoiding offset contention

## 4. Config, run & docs

- [x] 4.1 Add `HERMESMQ_CLUSTER_*` env overrides and a documented two-node `docker-compose.cluster.yml` (Artery ports, canonical hostnames, shared Postgres, consistent seed-nodes)
- [x] 4.2 Manual smoke: start a single node → forms cluster, all APIs work (regression check). Optionally two nodes → both join, commands for a topic route to one owner
- [x] 4.3 Update `README.md`: clustering overview, running N nodes, cluster env config, and the **10a delivery caveat** (multi-node delivery completeness lands in 10b)

## 5. Final verification

- [x] 5.1 Full `sbt test` green across modules (single-node cluster path; no DB required for the sharded-entity tests)
- [x] 5.2 Confirm every scenario in `clustering`, the `topic-admin-api` delta, and the `message-delivery` delta maps to a verified test (or a documented manual check for multi-node)
- [x] 5.3 Run `openspec validate cluster-sharding`
