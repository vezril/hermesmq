# pubsub-api

Adds a REST surface for lease management so consumers can extend a lease before it
expires, or nack a message for immediate redelivery.

## ADDED Requirements

### Requirement: Modify ack deadline over REST

The API SHALL expose `POST /v1/subscriptions/{id}/modifyAckDeadline` accepting
`{"ackIds":[…],"ackDeadlineSeconds":N}`, which for each currently-outstanding LEASED
`AckId` sets its deadline to `now + N` seconds. `ackDeadlineSeconds: 0` SHALL make the
referenced messages immediately available for redelivery (a nack). The response SHALL
report which `AckId`s were modified and which were unknown/not-leased. A request for an
unknown subscription SHALL return `404`.

#### Scenario: Extending a lease pushes back its deadline
- **GIVEN** a subscription with a LEASED `ackId`
- **WHEN** `POST /v1/subscriptions/{id}/modifyAckDeadline` is called with that `ackId` and `ackDeadlineSeconds: 60`
- **THEN** the response is `200`, the `ackId` is reported modified, and its deadline becomes `now + 60s`

#### Scenario: Edge case — deadline of 0 nacks for immediate redelivery
- **GIVEN** a subscription with a LEASED `ackId`
- **WHEN** `modifyAckDeadline` is called with that `ackId` and `ackDeadlineSeconds: 0`
- **THEN** the message becomes available for redelivery on the next pull (does not wait for the sweep interval)

#### Scenario: Edge case — unknown ackId is reported, not fatal
- **GIVEN** a subscription that does not have `ackId` outstanding
- **WHEN** `modifyAckDeadline` is called with that `ackId`
- **THEN** the response is `200` and the `ackId` is reported as unknown/not-modified (the whole request is not rejected)
