# Flow Core Report

Current published package line: `0.9.5`
Package release status: `release-candidate`
Next package line: `0.9.6`
Active public standard version: `0.8.0`
Completed Core roadmap identity: `0.9.7.9 Intent Lowering and Diagnostic Honesty`
Core roadmap item status: `correction-required`
Active correction item: `0.9.7.9.11 Public Artifact Evidence and Verification Integrity`
Next Core roadmap item: `0.9.7.10 Bounded Semantic Closure Gate` (`blocked`)

## Active direction

PR #90 completed target-selection provenance and CLI status repair and was merged as `91de098f8f51176a76e34ef53bab1b424edaa8d0`. A later audit proved that five public evidence paths remained unsound:

1. multiple `StandardSurface` reports published literal `PASS` values rather than computed validation results;
2. `target-semantics-matrix.json` contained handwritten per-target capability claims unsupported by provider contracts;
3. artifact producer provenance and introduced-version metadata used decorative string tables with optimistic fallbacks;
4. required `derivedFrom` evidence was checked only for non-emptiness, not for resolvable references;
5. standard bundle verification treated substring occurrence in JSON text as structured proof.

Bounded correction `0.9.7.9.11` is active. It replaces these paths with computed status authorities, provider-backed target semantics, an exact artifact contract authority, fail-closed derivation validation and strict structured bundle verification. No passing validation is claimed yet. Closure item `0.9.7.10` remains blocked.

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
11. `0.9.7.9.11 Public Artifact Evidence and Verification Integrity` (`active`)

## Retained implementation boundaries

### Parser, lowering and conformance

- Retry defaults apply only to omitted fields; malformed, fractional, unknown and duplicate declarations fail closed.
- Compliance exercises passing and failing conformance polarity.
- Universal evidence uses a target-neutral Core fixture.
- Serializer and module-governance boundaries remain mechanically enforced.

### Target selection and CLI outcomes

- Target materialization requires registry-validated explicit selection evidence.
- `CompatibilityReport.target` cannot become target-selection provenance.
- Target-selection origins remain a closed set.
- CLI outcomes derive success, review-required, blocked, invalid-input and internal-error process status from sealed result types.
- The duplicate CLI materialization pipeline remains absent.

## v0.9.7.9.11 implementation boundary

### Computed public status

- Public surface, compatibility policy, reference corpus, reference harness, export bundle, conformance levels and export manifest status are computed from their contents.
- Every status authority has a negative counterexample proving malformed or incomplete content returns `FAIL`.
- Public status fields are no longer self-certified literals.

### Provider-backed target semantics

- Target ids come from the target registry.
- Condition support comes from `TargetExpressionSupport`.
- Approval support comes from provider-owned `TargetNativeApprovalProjectionDefinition` contracts.
- Secret and artifact support comes from typed native projection bindings.
- Missing provider evidence is published as review-only rather than as a positive target capability.
- Jenkins owns native manual approval; GitHub Actions and Tekton remain adapter-required review-only.

### Artifact contract and derivation integrity

- Producer identity and introduced-version metadata come from one exact artifact contract authority.
- Unknown public artifacts fail closed; there is no generic producer or `pre-0.3.10` fallback.
- Required derived artifacts must reference another declared artifact or a registered external evidence source.
- Non-empty dangling references block `evidence.complete` and therefore block public compliance.

### Structured bundle verification

- `conformance-manifest.json` and `standard-export-bundle.json` are parsed into typed models.
- Unknown fields and duplicate JSON keys fail closed.
- Conformance requires exact standard version, `PASS` status, consistent counts, exact required-check membership and absence from `failedChecks`.
- Stable artifacts must occur specifically in `requiredArtifacts`.
- A failed gate, malformed JSON or unrelated textual mention cannot satisfy verification.

## Version boundary

The implementation package remains `0.9.5`, the public standard remains `0.8.0`, and the artifact contract remains `2.0`. This correction changes implementation honesty, not the published version axes.

## Roadmap order

1. `0.9.7.1` through `0.9.7.8` completed
2. `0.9.7.9 Intent Lowering and Diagnostic Honesty` `correction-required`
3. `0.9.7.9.10 Target Selection Provenance and CLI Status Integrity` completed and merged
4. `0.9.7.9.11 Public Artifact Evidence and Verification Integrity` active
5. `0.9.7.10 Bounded Semantic Closure Gate` blocked

## Validation

Historical `0.9.7.9.9` implementation and completion metadata passed Flow CI #2087 and #2094 before PR #88 merged.

Historical `0.9.7.9.10` implementation and completion metadata passed Flow CI #2100 and #2108 before PR #90 merged.

No external exact-head CI evidence is claimed for active `0.9.7.9.11`. The public implementation head and its synthetic merge candidate must independently pass Flow Agent tooling, repository structure, complete tests and standalone conformance. A later completion-metadata head must pass the same two jobs before review readiness.

## Architecture boundary

Flow remains a notes-driven universal automation language and standardization model. Core does not provide task execution, an SDK lifecycle, a plugin framework, dynamic discovery, command transport, shell projection, hidden continuity transfer, implicit implementation binding, target-owned control meaning or permissive release claims. Public evidence is accepted only through typed, falsifiable and fail-closed authorities.
