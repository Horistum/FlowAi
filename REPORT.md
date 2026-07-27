# Flow Core Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed Core roadmap identity: `0.9.7.9 Intent Lowering and Diagnostic Honesty`
Core roadmap item status: `completed`
Completed correction item: `0.9.7.9.11 Public Artifact Evidence and Verification Integrity`
Next Core roadmap item: `0.9.7.10 Bounded Semantic Closure Gate` (`next`)

## Active closure direction

The v0.9.7 universal semantic foundation is in the explicit `READY` phase. The finite closure authority, release metadata lifecycle, JSON schema, negative lifecycle tests and completed-track Flow Agent tooling are implemented. Closure metadata was temporarily advanced to `CLOSED`, but Flow CI #2169 correctly rejected it because an older closure-blocking conformance integration still treated correction completion as permanent proof that the closure item must remain `next`.

That stale invariant has been repaired at `ClosureBlockingIntegrityChecks`: conformance now consumes the authoritative `READY/CLOSED` phase and validates the complete lifecycle combination rather than independently re-deriving closure status from the correction work package.

## Correction ledger

1. `0.9.7.9.1 Canonical Lowering Regression Repair`
2. `0.9.7.9.2 Typed Literal and Reference Integrity`
3. `0.9.7.9.3 Environment Safety Production Integration`
4. `0.9.7.9.4 Scenario Negation and Token Boundary Honesty`
5. `0.9.7.9.5 Provider-Backed Approval and Topology Identity`
6. `0.9.7.9.6 Derived Model and Governance Integrity`
7. `0.9.7.9.7 CLI Diagnostic and Release Honesty`
8. `0.9.7.9.8 Closure-Blocking Safety, Governance and Diagnostic Integrity`
9. `0.9.7.9.9 Parser, Conformance and Architecture Evidence Integrity`
10. `0.9.7.9.10 Target Selection Provenance and CLI Status Integrity`
11. `0.9.7.9.11 Public Artifact Evidence and Verification Integrity` (`completed`)

## v0.9.7.10 closure boundary

### Finite closure authority

`SemanticClosureAuthority` evaluates exactly nine declared checks:

1. `closure.checklist-exact`
2. `closure.no-active-corrections`
3. `closure.prior-core-items-complete`
4. `closure.release-metadata-honest`
5. `closure.required-checks-present`
6. `closure.required-checks-pass`
7. `closure.no-failed-conformance`
8. `closure.version-boundary-unchanged`
9. `closure.reference-evidence-live`

The closure check runs last against an immutable snapshot of prior conformance results. It cannot certify itself or hide a failed non-release check.

### Explicit release lifecycle

`ReleaseMetadataHonestyAuthority` exposes two valid phases:

- `READY`: corrections are complete, the closure work package is active, the Core track is active and item `0.9.7.10` is next.
- `CLOSED`: the closure work package, Core track and item `0.9.7.10` are completed and structured implementation evidence records distinct exact-head and merge-candidate SHAs.

Every mixed lifecycle is `INVALID`. Completed metadata without a positive Flow CI run, exact SHA, merge SHA or distinct candidates fails closed. The report schema is aligned with runtime contract version `1.3` and permits `closureStatus: completed`.

### Closure-blocking conformance integration

`governance.closure-blocking-safety-diagnostic-integrity` now requires the release metadata authority to pass and then verifies the exact phase:

- `READY` requires closure work package `active`, closure item `next`, Core track `active` and completed item `0.9.7.9`.
- `CLOSED` requires closure work package `complete`, closure item `completed`, Core track `completed` and completed item equal to the closure identity.

`INVALID` remains blocking. This is not a permissive `next|completed` exception.

### Completed-track tooling

Flow Agent validation and context generation accept a primary track without a next item only when the track and every Core item are completed, no active correction remains, and the roadmap index identifies `0.9.7.10` as completed. An active or incomplete track without a next item remains an error.

### Negative evidence

Tests prove failure for missing required evidence, failing prior conformance, reactivated corrections, checklist drift, completed closure without implementation evidence, a completed closure with an active Core track, stale completed-item metadata, an active closure publishing completed status and an incomplete primary track without a next item. Lifecycle fixtures normalize their source state explicitly and remain valid before and after repository closure.

## Version boundary

The implementation package remains `0.9.5`, the public standard remains `0.8.0`, and the artifact contract remains `2.0`.

## Validation history and current boundary

Flow CI #2163 passed the lifecycle implementation before the first completion metadata transition.

Flow CI #2169 then provided negative completion evidence:

- completed-track Flow Agent tooling and structure passed;
- merge-candidate full tests rejected the stale closure-blocking conformance assumption;
- no completion claim was retained.

A new exact-head and synthetic merge-candidate implementation run must pass after the conformance lifecycle repair. Only then may a later metadata head restore `CLOSED` and undergo a second independent dual validation.

## Architecture boundary

Flow remains a notes-driven universal automation language and standardization model. Core does not provide task execution, an SDK lifecycle, a plugin framework, dynamic discovery, command transport, shell projection, hidden continuity transfer, implicit implementation binding, target-owned control meaning or permissive release claims. Closure consumes existing typed evidence and introduces no new semantic meaning.
