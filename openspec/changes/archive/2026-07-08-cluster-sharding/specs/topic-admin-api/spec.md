## MODIFIED Requirements

### Requirement: Single-writer topic registry

The service SHALL route topic commands to the persistent `TopicEntity` that owns each topic id via **Cluster Sharding**, ensuring **exactly one entity instance (one writer) per topic id across the cluster**. Entities SHALL be created on demand (on the owning node) and recover their own state from the shared journal; commands SHALL be routable from any node.

#### Scenario: Commands for the same topic reach one entity
- **GIVEN** two commands for the same topic id arrive (from any node)
- **WHEN** sharding routes them
- **THEN** both are handled by the same entity instance that owns that id in the cluster (no second, competing writer)

#### Scenario: A new topic id is resolved on demand
- **GIVEN** no entity is currently running for a topic id
- **WHEN** a command for that id arrives
- **THEN** sharding starts the entity on its owning node and the command is handled

#### Scenario: Edge case — commands for different topics are isolated
- **GIVEN** commands for two different topic ids
- **WHEN** sharding routes them
- **THEN** they are handled by separate entity instances (possibly on different nodes) and do not interfere
