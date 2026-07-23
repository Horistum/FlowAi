# v0.9.7.9.2 Artifact-Derived Lowering Evidence

## Purpose

Flow lowering evidence must describe what the canonical pipeline actually produced. It must not infer preservation from the presence of a source field, an array index or a planned target path that was never dereferenced.

This work item replaces source-iterated evidence with a two-stage contract:

1. Standard Intent lowering creates a stable source field catalog while the original structured intent is available.
2. FlowPlanner creates the lowering report only after the concrete ExecutionPlan exists and every catalog entry has been resolved against a real plan value.

## Version boundary

The public ExecutionPlan artifact remains version `2.0`.

The embedded lowering evidence contract advances from `1.0` to `2.0`. Consumers that deserialize `loweringReport` must migrate from the old path-only fields to stable identities and value digests.

The contract change is intentional because the old evidence could claim `PRESERVED` without proving that the target field existed or contained the accepted value.

## Stable identity model

Each accepted field is represented by an `IntentSourceField` containing:

- `identity`: semantic source identity, independent of list position
- `sourcePath`: human-readable source location for diagnostics
- `targetIdentity`: semantic address of the concrete execution-plan value
- `disposition`: `PRESERVED` or `TRANSFORMED`
- `valueKind`: canonical value domain used by digest calculation
- `sourceDigest`: SHA-256 digest of the canonical source value
- `expectedTargetDigest`: SHA-256 digest of the expected concrete target value
- `transform`: required named transformation for `TRANSFORMED`

`sourcePath` is not an identity authority. Reordering YAML lists may change a source path, but it must not change `identity` or `targetIdentity`.

## Report issuance

IntentToAstPlanner stores the source catalog in metadata and deliberately leaves `loweringReport` empty. An AST is not an execution plan and cannot honestly certify execution-plan values.

FlowPlanner completes all nodes, dependencies, outputs, control evidence and topology requirements before invoking `IntentLoweringAuthority.report(plan)`.

The resulting report contains:

- contract version `2.0`
- artifact kind `execution-plan`
- `evidenceDigest`, which is the aggregate digest of the certified evidence view
- one evidence record for every accepted source field

`evidenceDigest` is not a signature and does not claim to hash the entire ExecutionPlan. It is a deterministic integrity value for the lowering evidence set.

## Preservation and transformation rules

A `PRESERVED` entry is valid only when:

- its target identity resolves to exactly one concrete value
- the concrete target digest equals the expected target digest
- the source digest equals the concrete target digest
- no transform is declared

A `TRANSFORMED` entry is valid only when:

- its target identity resolves to exactly one concrete value
- the concrete target digest equals the expected transformed digest
- a non-empty transform name explains the semantic conversion

Examples of explicit transformations include:

- intent input type normalization
- step ID conversion to `sourceId` plus `resultName`
- explicit module/action binding resolution
- runtime input reference binding
- canonical system type normalization

## Mandatory materialization verification

The mandatory materialization boundary validates both structure and meaning:

- stable source identities are unique
- target identities are uniquely claimed
- all digests use lowercase SHA-256 format
- every source field has exactly one evidence record
- no report entry exists outside the source catalog
- every target identity resolves against the supplied plan
- the entire report equals a fresh report re-derived from the plan

A changed task parameter, missing node, forged evidence digest, duplicate entry or stale report blocks materialization.

## Removed behavior

The following behavior is removed:

- source list indices as target authority
- reports generated directly from `IntentDocument`
- path-only `PRESERVED` assertions
- `dropBlockedParams`
- synthetic `projection: notes-driven-materialization-required` markers

Runtime command text remains rejected before AST lowering under the v0.9.7.9.1 semantic boundary.

## Evidence and tests

`ArtifactDerivedIntentLoweringEvidenceTests` covers:

- report issuance after plan construction
- stable identities under topological source-step reordering
- value-backed preserved and transformed claims
- concrete target value tampering
- forged aggregate evidence digest
- missing and duplicate evidence entries
- successful mandatory materialization validation for an intact plan

The canonical `build-test-deploy` and `checkout-build-image` snapshot bundles are generated through `ReferenceSnapshotBundleGenerator` and contain lowering evidence contract `2.0`.
