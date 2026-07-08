## 1. Value types — TDD (Red → Green → Refactor)

- [ ] 1.1 RED: `IdentifiersSpec` — valid `TopicId` construction, value equality, blank rejected (`Either` left), and a type-distinctness check (compile-time; assert via separate types) for `TopicId`/`SubscriptionId`/`MessageId`/`AckId`; confirm red
- [ ] 1.2 GREEN: implement the four ids as Scala 3 opaque types with `from(String): Either[ValidationError, _]` smart constructors; make tests pass
- [ ] 1.3 RED: `MessageSpec` — carries id/payload/attributes/publishTime; empty payload rejected; attributes immutable after construction; confirm red
- [ ] 1.4 GREEN: implement `Message` with immutable payload (defensive copy) + immutable attributes and non-empty-payload validation; pass
- [ ] 1.5 RED: `AckDeadlineSpec` — valid duration accepted, negative rejected; confirm red
- [ ] 1.6 GREEN: implement `AckDeadline` with validation; pass
- [ ] 1.7 REFACTOR: consolidate `ValidationError`, tidy smart-constructor style; re-run `sbt test` green

## 2. Command / event / rejection algebras

- [ ] 2.1 Define sealed `Rejection` ADT with clearly-named cases (`TopicAlreadyExists`, `TopicNotFound`, `SubscriptionAlreadyExists`, `SubscriptionNotFound`, `UnknownAckId`)
- [ ] 2.2 Define Topic `Command` (`CreateTopic`, `Publish`) and `Event` (`TopicCreated`, `MessagePublished`) sealed ADTs
- [ ] 2.3 Define Subscription `Command` (`CreateSubscription`, `RecordDelivery`, `Acknowledge`, `ModifyAckDeadline`) and `Event` (`SubscriptionCreated`, `MessageDelivered`, `MessageAcknowledged`, `AckDeadlineModified`) sealed ADTs
- [ ] 2.4 Verify `sbt compile` green (ADTs wire up)

## 3. Topic aggregate — TDD

- [ ] 3.1 RED: `TopicSpec` — `decide` on empty + `CreateTopic` → `Right(TopicCreated)`, and `evolve` marks existing; confirm red
- [ ] 3.2 GREEN: implement `Topic.decide`/`evolve` for create; pass
- [ ] 3.3 RED: publish tests — `Publish` on existing topic → `Right(MessagePublished)`; confirm red
- [ ] 3.4 GREEN: implement publish handling; pass
- [ ] 3.5 Edge cases: `CreateTopic` on existing → `Left(TopicAlreadyExists)`; `Publish` on non-existent → `Left(TopicNotFound)`; `decide`/`evolve` total (never throw) for all command/event combinations; implement/verify green
- [ ] 3.6 REFACTOR: tidy pattern matches; re-run green

## 4. Subscription aggregate — TDD

- [ ] 4.1 RED: `SubscriptionSpec` — `CreateSubscription` on empty → `Right(SubscriptionCreated)`, `evolve` marks existing with empty outstanding; confirm red
- [ ] 4.2 GREEN: implement create in `Subscription.decide`/`evolve`; pass
- [ ] 4.3 RED: record-delivery tests — `RecordDelivery` on existing → `Right(MessageDelivered)`, `evolve` adds outstanding `AckId`; confirm red
- [ ] 4.4 GREEN: implement record-delivery; pass
- [ ] 4.5 RED: acknowledge tests — `Acknowledge` of outstanding → `Right(MessageAcknowledged)`, `evolve` removes it; confirm red
- [ ] 4.6 GREEN: implement acknowledge; pass
- [ ] 4.7 RED: modify-deadline tests — `ModifyAckDeadline` of outstanding → `Right(AckDeadlineModified)`, `evolve` updates deadline; confirm red
- [ ] 4.8 GREEN: implement modify-deadline; pass
- [ ] 4.9 Edge cases: duplicate `CreateSubscription` → `Left(SubscriptionAlreadyExists)`; `RecordDelivery`/`Acknowledge`/`ModifyAckDeadline` on non-existent subscription or unknown `AckId` → appropriate `Left`; double-acknowledge → `Left(UnknownAckId)`; implement/verify green
- [ ] 4.10 REFACTOR: extract shared outstanding-lookup helper; re-run green

## 5. Final verification

- [ ] 5.1 Full `sbt test` green (all new suites + existing service tests)
- [ ] 5.2 Confirm every scenario in `domain-model`, `topic-lifecycle`, `subscription-lifecycle` maps to a verified test
- [ ] 5.3 Run `openspec validate core-domain-model`
