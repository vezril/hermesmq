## ADDED Requirements

### Requirement: Validated identifiers

Identifiers (`TopicId`, `SubscriptionId`, `MessageId`, `AckId`) SHALL be type-safe value wrappers that are non-empty and reject blank input at construction, returning a typed error rather than producing an invalid instance. Distinct identifier types SHALL NOT be interchangeable.

#### Scenario: Valid identifier is constructed
- **GIVEN** a non-empty string `"orders"`
- **WHEN** a `TopicId` is created from it
- **THEN** construction succeeds and the wrapped value is `"orders"`

#### Scenario: Identifiers compare by value
- **GIVEN** two `TopicId` values created from the same string
- **THEN** they are equal, and can be used as reliable map keys

#### Scenario: Edge case — blank identifier is rejected
- **GIVEN** an empty or whitespace-only string
- **WHEN** a `TopicId` (or any id type) is created from it
- **THEN** construction returns a validation error and no identifier is produced

#### Scenario: Edge case — identifier types do not mix
- **GIVEN** a `TopicId` and a `SubscriptionId`
- **THEN** the compiler prevents using one where the other is required (no accidental substitution)

### Requirement: Immutable message envelope

A `Message` SHALL be an immutable envelope carrying a `MessageId`, a payload (bytes), an attributes map, and a publish timestamp. Its attributes SHALL be immutable so a caller cannot mutate a message after construction.

#### Scenario: Message carries its fields
- **GIVEN** a `MessageId`, a payload, attributes `{"key":"v"}`, and a publish time
- **WHEN** a `Message` is constructed
- **THEN** it exposes exactly those values

#### Scenario: Edge case — empty payload is rejected
- **GIVEN** an empty payload
- **WHEN** a `Message` is constructed
- **THEN** construction returns a validation error (a message must carry a body)

#### Scenario: Edge case — attributes cannot be mutated after construction
- **GIVEN** a `Message` constructed from a mutable attributes source
- **WHEN** the original source is mutated afterwards
- **THEN** the `Message`'s attributes are unchanged

### Requirement: Acknowledgement deadline value type

An `AckDeadline` SHALL represent a non-negative duration (or absolute instant) for how long a delivered message may remain unacknowledged, and SHALL reject negative values at construction.

#### Scenario: Valid deadline is constructed
- **GIVEN** a duration of 30 seconds
- **WHEN** an `AckDeadline` is created
- **THEN** construction succeeds

#### Scenario: Edge case — negative deadline is rejected
- **GIVEN** a negative duration
- **WHEN** an `AckDeadline` is created
- **THEN** construction returns a validation error
