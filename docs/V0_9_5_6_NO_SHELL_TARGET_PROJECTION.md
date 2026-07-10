# v0.9.5.6 No-Shell Target Projection

## Purpose

v0.9.5.6 introduces the target projection plan contract for the notes-driven correction track.

The contract sits after semantic action graph validation and materialization negotiation. Its job is to record which projection artifacts may exist for each materialization decision without turning command strings into the universal representation of automation work.

## Projection artifacts

A target projection artifact is one of:

- `TARGET_NATIVE`: a structured artifact owned by a declared target boundary
- `NOTES_BACKED`: a projection record backed by a notes declaration
- `ADAPTER_BOUNDARY`: an explicit adapter requirement that cannot be represented as completed target work
- `REVIEW_RECORD`: a review or policy record for unavailable work
- `CONFORMANCE_RECORD`: verification evidence

## Validation rules

The validator enforces:

- projection plan id format
- valid materialization negotiation input
- every materialization decision has a projection artifact record
- each projection artifact references a known semantic node
- each projection artifact has a matching materialization decision
- artifact status must match the materialization decision status
- unavailable decisions cannot produce target-native or notes-backed artifacts
- adapter-required decisions remain adapter boundaries or review records
- target-native artifacts name a target boundary
- notes-backed artifacts cite a notes reference
- projection artifact text does not use shell, command or script vocabulary as representation

## Correction result

This step provides a projection contract before the existing target renderers are further constrained.

The important boundary is honesty: a projection plan may record a target-native artifact, notes-backed artifact, adapter boundary, review record or conformance record. It may not silently convert a semantic action into a generic command string and call that successful support.

## Relationship to later work

v0.9.5.6 prepares:

- v0.9.5.7 Target Registry Honesty
- v0.9.5.8 Policy-Driven Safety and Environment Classification
- v0.9.5.9 Trigger and Schedule Notes
- v0.9.5.10 Conformance Honesty Gates

## Versioning boundary

- Current package version remains `0.9.4`.
- Active public standard version remains `0.7.6`.
- This is roadmap correction work inside the v0.9.5.x correction track.
- No artifact schema version is changed.
