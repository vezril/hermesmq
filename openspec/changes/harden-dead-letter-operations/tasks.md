# Tasks

Test-first throughout. Each group lands the tests and documentation its own work calls for.

## 1. Paged repository reads

- [x] 1.1 Write failing `DeadLetterRepositorySpec` cases for a paged `listFor`: a page bounded by `limit`, a second page from a cursor that repeats nothing, and a `None` cursor meaning "from the newest"
- [x] 1.2 Add a failing case for tie-breaking — two entries sharing a `deadLetteredAt` must both appear exactly once across pages, ordered by message id
- [x] 1.3 Implement the paged `listFor(subscriptionId, limit, cursor)` on the trait plus both implementations, keyset-ordered on `(deadLetteredAt DESC, originalMessageId DESC)`; verify 1.1 and 1.2 pass
- [x] 1.4 Extend `DeadLetterRepositoryIntegrationSpec` to page against real PostgreSQL, including the shared-timestamp tie-break, and verify the existing index serves the query (no sequential scan) via `EXPLAIN`

## 2. Atomic redrive claim

- [x] 2.1 Write a failing repository spec for `claimForRedrive`: it returns true on an unclaimed entry, false on an already-claimed one, and false for an absent entry
- [x] 2.2 Add a failing case for `releaseClaim`, resetting `redriven_at` so a retry can claim again
- [x] 2.3 Add a failing case for a forced claim, which takes an already-claimed entry and updates its timestamp
- [x] 2.4 Implement `claimForRedrive(subscriptionId, messageId, at, force)` and `releaseClaim` on both implementations, replacing `markRedriven`; verify 2.1–2.3 pass
- [x] 2.5 Add an integration case proving the claim is atomic against real PostgreSQL: two concurrent claims of one entry, exactly one wins

## 3. Paginated browse endpoint

- [x] 3.1 Write failing route tests for the envelope response: `deadLetters` holds the page, `nextCursor` present when more remain and absent on the last page
- [x] 3.2 Add failing tests for `limit` (honoured, defaulted, clamped at the maximum) and for a malformed cursor returning `400`
- [x] 3.3 Add a failing test that walking the cursor to exhaustion yields every entry exactly once
- [x] 3.4 Implement the envelope, the opaque base64 cursor encode/decode, and the `limit`/`cursor` query parameters; verify 3.1–3.3 pass
- [x] 3.5 Update the README's dead-letter section: the new response shape (flagged BREAKING), `limit`/`cursor`, and the default and maximum page size; verify the documented curl runs as written

## 4. Redrive through the claim

- [x] 4.1 Rewrite the existing redrive route tests against the claim path — a repeat reports `alreadyRedriven` and publishes nothing, with the dedup window left at its `0s` default so the test proves independence from it
- [x] 4.2 Add a failing test that a failed publish releases the claim, so a retry republishes rather than reporting already-redriven
- [x] 4.3 Add a failing test for `force: true`, republishing an entry already marked redriven
- [x] 4.4 Implement claim-before-publish in `DeadLetterRoutes`, with release on publish failure and `force` honoured; verify 4.1–4.3 pass
- [x] 4.5 Update the README: remove the idempotency caveat, document `force` and when an operator needs it (a claim taken but the publish lost); verify the documented behaviour matches
- [x] 4.6 Remove the two roadmap rows this change closes (browse pagination, unconditional redrive idempotency) and keep the per-subscription dead-letter topic row

## 5. Integration verification

- [x] 5.1 Extend `DeadLetterFlowSpec` to walk a multi-page browse and then redrive from the last page, covering the seam between paging and redrive
- [x] 5.2 Add a flow case asserting two concurrent redrives of one entry publish exactly once end to end
- [x] 5.3 Verify the full gate: `sbt clean "scalafixAll --check" test` green with no new `-Werror` or `DisableSyntax` violations
- [x] 5.4 Covered by the `Integration (PostgreSQL)` CI job (Docker unavailable locally); the new paging and claim cases were added to `DeadLetterRepositoryIntegrationSpec`. Original wording: Verify the PostgreSQL suites: `sbt -Dit=true "testOnly *DeadLetterRepositoryIntegrationSpec *PostgresPersistenceIntegrationSpec *SchemaMigrationIntegrationSpec *ObservabilityIntegrationSpec"` — or confirm the `Integration (PostgreSQL)` CI job covers it if Docker is unavailable locally
- [x] 5.5 Confirm every internal README anchor added or changed here resolves

## Workflow follow-up

- Open one pull request into `development` and merge once CI is green.
- Archive with `openspec archive harden-dead-letter-operations`, folding the three modified requirements into the canonical `dead-letter-operations` spec.
- Release as a minor bump with the browse response shape called out as BREAKING in the release notes.
