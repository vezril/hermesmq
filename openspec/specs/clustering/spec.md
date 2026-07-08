# clustering Specification

## Purpose

Define running HermesMQ as a Pekko cluster: formation (seed nodes, remoting, split-brain resolution), cluster sharding of the Topic/Subscription entities, and a single cluster-wide delivery projection.

## Requirements

### Requirement: Cluster formation

The service SHALL run as a Pekko cluster: nodes form membership via configured `seed-nodes` over Artery remoting, and a split-brain-resolver downing provider SHALL keep membership consistent under network partitions. A single node SHALL form a one-node cluster with unchanged behavior.

#### Scenario: A single node forms a one-node cluster
- **GIVEN** a single instance configured to seed itself
- **WHEN** it starts
- **THEN** it forms a one-node cluster, becomes `Up`, and serves requests exactly as before

#### Scenario: Multiple nodes join one cluster
- **GIVEN** two nodes configured with the same `seed-nodes`
- **WHEN** both start
- **THEN** they form a single cluster and each becomes a member

#### Scenario: Edge case — a split-brain resolver is configured
- **GIVEN** the cluster configuration
- **WHEN** it is loaded
- **THEN** a downing provider (split-brain resolver) is active, so a network partition cannot leave two independent halves both believing they own an entity

### Requirement: Sharded entities with cluster-wide single writer

Topic and subscription entities SHALL be distributed with Cluster Sharding, keyed by their id, so that exactly one entity instance (one writer) exists per id **across the whole cluster**. Commands SHALL be routed to the owning entity via an `EntityRef`, from any node. Persistence ids SHALL be unchanged so existing journals remain compatible.

#### Scenario: Commands for the same id reach one entity cluster-wide
- **GIVEN** a sharded Topic (or Subscription) entity type
- **WHEN** commands for the same id are sent from any node(s)
- **THEN** they are all handled by the single entity instance that owns that id in the cluster

#### Scenario: A command from any node is routed to the owning entity
- **GIVEN** an entity owned by some node
- **WHEN** a command for its id arrives at a different node
- **THEN** it is forwarded to the owning entity and handled (location transparency)

#### Scenario: Edge case — entity recovers its state after rebalance/restart
- **GIVEN** a sharded entity with journaled events
- **WHEN** the shard is rebalanced to another node (or the node restarts)
- **THEN** the entity is re-created on its new node and recovers its state from the shared journal

### Requirement: Single cluster-wide delivery projection

The delivery projection SHALL run as exactly one instance across the cluster (a sharded daemon process / cluster singleton), so topic events are processed once and there is no duplicate delivery or offset-store contention between nodes.

#### Scenario: Only one projection instance runs in the cluster
- **GIVEN** a cluster of one or more nodes
- **WHEN** the delivery projection is started
- **THEN** exactly one instance is active cluster-wide, consuming the topic events

#### Scenario: Edge case — the projection fails over on node loss
- **GIVEN** the node running the projection leaves the cluster
- **WHEN** membership converges
- **THEN** the projection is restarted on a remaining node and resumes from its stored offset
