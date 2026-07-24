# v0.9.7.9.4 Scenario Negation and Token Boundary Honesty

## Purpose

Scenario normalization must preserve explicit negative meaning and must match semantic phrases at token boundaries. A noun appearing inside a denial, another identifier or an unrelated sentence is not permission to synthesize work.

## Source directive model

The normalizer uses one target-neutral source directive authority. It records:

- the semantic concept
- every exact token-sequence mention
- whether each mention is affirmed or negated
- the aggregate status: `REQUESTED`, `DENIED`, `CONFLICTING` or `ABSENT`

The authority covers backup, repository access, notification, approval, rollback and restore. It is shared by scenario selection, scenario synthesis and the independent contradiction validator.

## Token boundary contract

Scenario triggers and source directives match complete token sequences. Substrings do not count.

Examples:

- `ci` does not match `capacity`
- `git` does not match `digital-api`
- `checkout` does not match `checkout-api`
- a bare reference to a `team` does not request notification

Multi-word phrases must be contiguous. The old ordered-token search, which could join unrelated words across a sentence, is removed.

Exact trigger selection also includes lexical specificity. When two packs match the same number of phrases, a multi-token trigger carries more evidence than a generic one-token noun. This prevents a request such as `migrate database orders and create a backup` from being classified as a generic backup operation merely because that pack is registered first. Common grammatical forms such as `migrate the database` are declared explicitly rather than recovered through fuzzy matching.

## Negation contract

Negation is clause-local. The authority recognizes prefix and suffix forms, including:

- `no backup`
- `without creating a backup`
- `do not notify`
- `skip repository checkout`
- `backup is unavailable`
- `backup is not configured`

A purely negated trigger does not select its scenario pack. A denied concept does not create a system, step, failure policy or positive control parameter.

If affirmed and negated evidence coexist, normalization does not choose one silently. It emits a required clarification and withholds the disputed synthesis.

## Backup and restore behavior

Database migration creates a backup step and positive `backup` parameter only from affirmed backup evidence. Explicit denial remains unmitigated and blocks through the existing control and risk authorities without fabricating contradictory evidence.

The backup/restore pack no longer creates a new backup for a restore-only request. An explicitly denied backup is omitted while the affirmed restore operation remains represented.

## Repository behavior

Optional Git/source systems are created only from affirmed repository evidence, an explicit source requirement of the scenario, or provided repository context. Explicit denial or conflicting evidence suppresses the source system and requires clarification when contradictory.

## Notification behavior

Notification requires an affirmed communication verb or channel. `notify`, `email`, `slack`, `notification` and equivalent exact phrases are supported. The noun `team` alone is not notification evidence.

Denied or conflicting notification evidence cannot create:

- a notifier system
- a `NOTIFY` step
- `failure.notify = true`

## Defensive validation

`IntentSourceContradictionAuthority` remains an independent fail-closed validator. It now consumes the same lexical evidence instead of maintaining a second regex vocabulary. Its purpose is defense in depth for manually constructed or future normalized intents, not routine repair of the standard scenario packs.

## Boundary

This work does not add probabilistic NLP, target-specific vocabulary, a runtime text interpreter or silent conflict resolution. Unsupported or contradictory meaning remains explicit and reviewable.
