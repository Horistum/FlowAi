# Reference Intent Corpus

Introduced in v0.4.7. Executable since corpus version 2.0.

The reference intent corpus defines portable examples for the standard. It is
not a target template library. Its purpose is to preserve the semantics of
human and AI intent through normalization, validation, decision reporting and
lowering.

## Executable corpus

Every scenario carries the actual free-text input and a verified expectation.
The conformance check `v0.4.7.reference-intent-corpus` replays each scenario
through the real `ScenarioPackIntentNormalizer` and the `IntentProposalReview`
gate and asserts the produced status, capabilities, required clarifications,
rejection codes and extracted entities. The corpus is therefore verified against
real behavior rather than asserted against itself: if normalization, the
clarification model or a safety mandate changes, the check fails.

Entity verification matters because a scenario could otherwise pass with a
wrongly extracted entity. For example, a Kubernetes scope that is mis-parsed
would still produce an accepted status; asserting the expected scope value
catches that.

A scenario is `ACCEPTED` when it raises no required clarification and the gate
accepts it. It is `BLOCKED` when the normalizer raises a required clarification
or the proposal-review gate rejects it.

## Accepted scenarios

- build and test,
- deployment with approval, verification and rollback,
- cleanup with explicit retention,
- Kubernetes maintenance with dry-run (expected scope `payments`),
- database migration with backup and rollback.

## Blocked scenarios

Negative scenarios are first-class corpus entries; they must remain blocked
before rendering.

Blocked on a required clarification:

- deployment with a missing application (`entities.application.name`),
- cleanup without retention (`safety.cleanup.retention`),
- secret rotation without a concrete secret name (`entities.secret.name`),
- certificate renewal without an identified certificate (`entities.certificate`).

Blocked by the proposal-review gate:

- database migration without backup, rejected on the mandated obligation
  `SAFETY_REQUIRES_BACKUP`.

## Note on database migration

The two migration scenarios document the layered safety model. A database
migration is treated as high risk. Without a backup the gate rejects on the
backup obligation (`SAFETY_REQUIRES_BACKUP`). A migration that pairs a backup
step with an explicit rollback path mitigates the high risk and is accepted. A
migration with a backup but no rollback would remain blocked on
`SAFETY_UNMITIGATED_HIGH_RISK`, so backup alone is not sufficient.

The backup question itself is emitted as a recommended clarification, not a
required one; the migration without backup is blocked by the gate, not by a
required clarification.
