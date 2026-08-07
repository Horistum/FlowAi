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
- accepted as authored source input by the explicit compatibility boundary;
- preserved as a deprecated source-code compatibility alias while package version remains `0.9.5`;
- normalized immediately to `CLUSTER_MAINTENANCE`;
- prevented from becoming canonical semantic identity or participating in enum iteration.

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

The published implementation package remains `0.9.5`. Later `v0.9.5.x` through `v0.9.7.x` identifiers are historical or unreleased correction/work-item tracks, not package versions. The existing deprecated Kotlin compatibility alias is retained in this correction, so the PR does not introduce a same-version source break. Removal of that alias belongs to a separately owned future publication boundary and must not overwrite an already published `0.9.5` artifact with a different API surface.

### Generator package ownership

The entire `generators/` directory is not Core. It contains target-specific rendering and provider contracts. Only explicitly named target-neutral authorities, currently `MandatoryMaterializationAuthority` and `ExecutionPlanTopologyValidator`, are included in the semantic neutrality boundary.

### Authority class count

The number and structural similarity of `*Authority` classes is a maintainability smell, not a proven behavioral or governance defect. A broad lifecycle-authority refactor is outside this correction because it would mix architectural cleanup with a safety and semantic integrity fix. The cross-stream architecture backlog records a separate `AR0.1 Authority Responsibility Consolidation` item to inventory authority ownership, remove forwarding-only ceremony and consolidate only where invariant boundaries remain explicit.

## Completion evidence

Completion requires:

1. canonical and legacy input tests;
2. scanner positive and negative polarity tests;
3. direct safety and mandatory-materialization unknown-action tests;
4. public corpus and conformance vector consistency;
5. full exact-head and synthetic merge-candidate Flow CI;
6. no weakening of frozen Core, adapter or conformance inventories.

## Source compatibility and evidence parsing hardening

The retired `KUBERNETES_MAINTENANCE` spelling remains absent from canonical enum identity and normative schemas, but the deprecated Kotlin source alias is preserved while the package stays at `0.9.5`. Legacy authored intent is also supported through a strict source-boundary manifest and normalized immediately to `CLUSTER_MAINTENANCE`.

The compatibility manifest is SHA-256 pinned. Only the reviewed manifest bytes and exact retired `source:` entry are descriptive compatibility evidence. The Kotlin compatibility-symbol exemption is likewise identity-bound: a declaration is exempt only when its symbol name is present in `StandardCapabilityCompatibility.retiredSourceNames`. A newly invented platform alias such as `JENKINS_PIPELINE`, `DOCKER_BUILD` or `TEKTON_TASK` remains actionable even when it uses the exact compatibility-property declaration shape. A changed manifest or a new platform-specific alias therefore fails the neutrality gate until the compatibility contract is explicitly reviewed.

All shared YAML map reads use duplicate-key detection. Governance, roadmap and evidence documents therefore cannot collapse duplicate keys through last-wins parsing before their field validation runs.

The neutrality scanner has no file-wide catalog or compatibility exemptions. It recognizes only direct `catalogModules(...)` declarations, the pinned compatibility source entry and declared retired Kotlin compatibility identities. Predicate calls, regular-expression construction, quoted JSON control keys and Kotlin interpolation expressions are actionable when they carry concrete platform meaning.

Parser and validator recursion is bounded. Excessive expression, statement or manually constructed AST nesting produces a typed diagnostic instead of escaping the CLI as `StackOverflowError`. Numeric literals reject a second decimal point lexically, and retry counts require an integer in the supported range.
