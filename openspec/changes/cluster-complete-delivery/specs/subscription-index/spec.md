## ADDED Requirements

### Requirement: Durable topic→subscriptions read model

The service SHALL maintain a durable, queryable read model mapping each topic to the subscriptions bound to it, backed by the shared database and populated by a projection over `SubscriptionCreated` events. It SHALL be readable by any node and SHALL survive a full-cluster restart (rebuildable from the journal).

#### Scenario: A created subscription appears in the read model
- **GIVEN** a subscription `s1` is created bound to topic `orders` (its `SubscriptionCreated` event is processed)
- **WHEN** the read model is queried for `orders`
- **THEN** it returns `s1`

#### Scenario: The read model is shared across nodes
- **GIVEN** a subscription created via one node
- **WHEN** any node queries the read model for that topic
- **THEN** it returns the subscription (the model is backed by the shared database, not per-node memory)

#### Scenario: Edge case — subscriptions on different topics are isolated
- **GIVEN** subscription `s1` on `orders` and `s2` on `billing`
- **WHEN** the read model is queried for `orders`
- **THEN** it returns `s1` and not `s2`

#### Scenario: Edge case — the projection is idempotent on replay
- **GIVEN** a `SubscriptionCreated` for `s1`/`orders` already applied
- **WHEN** the projection re-processes the same event (after a restart/failover)
- **THEN** the read model still contains exactly one `s1` entry for `orders` (upsert, no duplicate)

### Requirement: Single cluster-wide index projection

The subscription-index projection SHALL run as exactly one instance across the cluster (a sharded daemon process), resuming from its stored offset.

#### Scenario: One index-projection instance runs in the cluster
- **GIVEN** a cluster of one or more nodes
- **WHEN** the subscription-index projection is started
- **THEN** exactly one instance is active cluster-wide, maintaining the read model
