# Core Platform Neutrality and Fail-Closed Integrity

## Purpose

This correction resolves substantiated findings from the post-C0.2 architecture audit without converting a lexical inventory into a noisy platform-name ban.

Flow Core owns target-neutral intent, effects, controls, dependency continuity and topology meaning. Concrete platforms may appear in adapters, scenario inputs, compatibility diagnostics and evidence, but they cannot define canonical Core semantics.

## Confirmed findings

### Platform-specific canonical capability

`StandardCapability.KUBERNETES_MAINTENANCE` was a real platform-specific semantic identifier in Core. It originated as a retained compatibility symbol, but the exception had no bounded source-only contract and propagated into canonical effects, contracts, public schemas and reference artifacts.

The canonical capability is now `CLUSTER_MAINTENANCE`.

The legacy name is:

- absent from the enum values;
- absent from normative schemas and public reference capability output;
- accepted only by the explicit source compatibility boundary;
- normalized immediately to `CLUSTER_MAINTENANCE`;
- available to source callers only as a deprecated non-enum alias.

Concrete Kubernetes maintenance scenarios remain valid evidence inputs. They produce the target-neutral cluster-maintenance capability.

### Tautological platform-neutrality test

The previous repository test recomputed the same expression used by `healthStatus` and compared the result with itself. It proved internal consistency, not repository cleanliness.

The repository gate now requires:

- `healthStatus == PASS`;
- no actionable semantic evidence;
- explicit non-actionable classification for descriptive catalog, diagnostic and compatibility evidence.

Negative tests prove that semantic identifiers, control literals, semantic literals, Kotlin interpolation code and normative schema values fail the gate.

### Scanner blind spots

The neutrality inventory now:

- scans Kotlin interpolation expressions as executable code;
- scans `schemas/`, top-level `adapters/` and `verification/`;
- treats named target-neutral authorities in `generators/manifest/` as Core-owned semantic boundaries;
- keeps target generators and adapter evidence in the adapter boundary;
- distinguishes semantic/control literals from diagnostics and descriptive catalog metadata;
- covers Azure DevOps, GitLab CI, CircleCI, Helm, kubectl, Terraform and `k8s` in addition to the original terms.

A platform name in documentation or a diagnostic is not automatically a semantic defect. The gate fails only when concrete vocabulary owns executable or normative meaning in a Core production boundary.

### Unknown action contracts

`SafetyBoundaryValidator` previously returned without a safety finding when an action contract was unknown. Planning validation also skipped module continuity and module-effect re-derivation for unknown actions.

The independent fail-closed boundaries are now:

- `SAFETY_ACTION_CONTRACT_UNKNOWN` at the safety boundary;
- `planning.action.contract.missing` at mandatory materialization.

Upstream `ACTION_NOT_FOUND` remains intact. The additional findings prove defense in depth rather than relying on validator call ordering.

## Bounded or rejected findings

### Package and correction-track versions

The published implementation package remains `0.9.5`. Later `v0.9.5.x` through `v0.9.7.x` identifiers are historical or unreleased correction/work-item tracks, not package versions. The build comment now states both axes explicitly. No version change is part of this correction.

### Generator package ownership

The entire `generators/` directory is not Core. It contains target-specific rendering and provider contracts. Only explicitly named target-neutral authorities, currently `MandatoryMaterializationAuthority` and `ExecutionPlanTopologyValidator`, are included in the semantic neutrality boundary.

### Authority class count

The number and structural similarity of `*Authority` classes is a maintainability smell, not a proven behavioral or governance defect. A broad lifecycle-authority refactor is outside this correction because it would mix architectural cleanup with a safety and semantic integrity fix.

## Completion evidence

Completion requires:

1. canonical and legacy input tests;
2. scanner positive and negative polarity tests;
3. direct safety and mandatory-materialization unknown-action tests;
4. public corpus and conformance vector consistency;
5. full exact-head and synthetic merge-candidate Flow CI;
6. no weakening of frozen Core, adapter or conformance inventories.
