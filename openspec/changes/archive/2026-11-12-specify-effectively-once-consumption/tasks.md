# Tasks

Tests before docs: the point of this change is that the guarantee is held in place by tests, so they
come first and must pass against unmodified production code. If any of them fails, the guarantee does
not actually hold and this change becomes a bug fix instead.

## 1. Conformance tests for key stability

- [x] 1.1 Add a test that a message redelivered after its ack deadline lapses carries the same `ackId` as the first delivery; verify it passes against unchanged production code
- [x] 1.2 Add a test that a message redelivered after a nack (`ackDeadlineSeconds: 0`) carries the same `ackId`
- [x] 1.3 Add a test that a projection replay of the same `MessagePublished` re-delivers under the same `ackId`
- [x] 1.4 Add a test that one message fanned out to two subscriptions yields two distinct `ackId`s, so one subscription's dedup state cannot suppress another's delivery
- [x] 1.5 Add a consumer-perspective test: a stub consumer that records processed `ackId`s processes a redelivered message exactly once; verify it demonstrates the documented recipe end to end

## 2. Documentation

- [x] 2.1 Add a README "Delivery semantics" subsection stating at-least-once, the stable `ackId` contract, and where the guarantee stops (the crash-after-write case); verify the documented claims match the tests in group 1
- [x] 2.2 Document the effectively-once recipe — `idempotencyKey` on publish plus `ackId` dedup on consume — with a worked example, and note that the consumer chooses its own key-retention horizon
- [x] 2.3 Rewrite the roadmap's exactly-once row into a boundary statement explaining why it is not offered and pointing at the recipe; verify no roadmap row still promises exactly-once
- [x] 2.4 Confirm every internal README anchor added or changed here resolves

## 3. Verification

- [x] 3.1 Verify the full gate: `sbt clean "scalafixAll --check" test` green, with no production-code diff in this change
- [x] 3.2 Confirm `git diff` touches only tests, docs, and `openspec/` — any production change means the guarantee did not hold and the proposal needs revisiting

## Workflow follow-up

- Open one pull request into `development` and merge once CI is green.
- Archive with `openspec archive specify-effectively-once-consumption`.
- No release needed on its own; it ships with whatever lands next.
