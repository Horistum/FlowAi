# AR0.1 Authority Responsibility Consolidation

## Purpose

AR0.1 is an ownership audit before it is a refactor. Its completion baseline contained 74 production Kotlin types whose names end in `Authority`; that historical count is not a ceiling. The live catalog is intentionally maintained as later work adds, removes or rewires independently justified authorities. The number itself is not a defect. The architectural question is whether each type owns a distinct invariant or orchestration boundary, whether its callers reflect that ownership, and whether repeated code is duplicated policy or merely duplicated mechanism.

The acceptance criterion is therefore not a smaller class count. A smaller count can be a side effect of simplification, but collapsing independent invariants into one configurable authority would make the architecture less explicit while producing an impressive-looking deletion diff. Software has survived enough of those bargains.

## Inventory model

`standard/architecture/authority-responsibilities.yaml` is the canonical AR0.1 inventory. `AuthorityResponsibilityCatalog` discovers production Kotlin definitions lexically, excluding comments and string literals, and requires an exact catalog entry for every type ending in `Authority`.

Each entry records:

- the definition path;
- one responsibility role;
- the invariant owned by the type;
- its input boundary;
- its output boundary;
- the exact production source files that reference it.

The caller set is deliberately checked rather than written as prose only. A new authority, a removed authority, a moved definition or a changed production dependency edge makes the catalog stale and fails the AR0.1 architecture guard. This converts the inventory from a one-time review artifact into a maintenance contract for future development.

### Responsibility distribution at the AR0.1 baseline

| Role | Count | Architectural meaning |
| --- | ---: | --- |
| semantic invariant owner | 17 | Owns target-neutral meaning or canonical decisions. |
| evidence integrity owner | 16 | Independently rejects incomplete, contradictory or promoted evidence. |
| lifecycle boundary | 13 | Owns one roadmap item's historical state/evidence or the global handoff. |
| adapter policy owner | 13 | Matches frozen neutral requirements against adapter-owned evidence. |
| target-edge policy owner | 5 | Owns behavior where neutral state becomes target-facing diagnostics/materialization. |
| release policy owner | 4 | Owns release, closure and publication honesty. |
| identity owner | 3 | Owns deterministic semantic identity and collision behavior. |
| orchestration boundary | 3 | Composes already-owned decisions without becoming semantic truth itself. |

These categories are not layering shortcuts. Dependency direction is still determined by packages and frozen contracts; the role merely explains why an authority exists.

## Call-graph findings

Every current production `*Authority` type has at least one production caller. There is therefore no class that can be removed honestly on the simplistic basis of being completely unreachable. The useful findings are structural instead:

1. lifecycle authorities repeatedly represented and parsed the same Flow CI boundary fields (`status`, workflow name, run number/id, exact head and merge candidate) and independently reimplemented the same structural-validity test;
2. control, continuity and trigger adapter authorities independently own different assessment policy, but after a blocking assessment they performed the same manifest compatibility mutation and mapping-note projection;
3. lifecycle state machines themselves are similar in shape but not equivalent in policy: predecessor evidence, historical preservation rules and legal successor states differ;
4. control, continuity and trigger assessment logic is also intentionally distinct: the requirement identities, closed evidence states, completeness checks and preservation semantics differ.

The distinction between duplicated mechanism and duplicated policy drives the implementation below.

## Consolidation 1: canonical workflow-boundary evidence

`WorkflowBoundaryEvidence` now owns the cross-stream structural definition of one trustworthy Flow CI boundary. It validates:

- presence;
- the exact `Flow CI` workflow identity;
- positive run number and run id;
- exact 40-character lower-case Git SHA values;
- distinct exact-head and synthetic merge-candidate revisions;
- absence of unknown evidence fields.

It also owns exact-boundary equality and monotonic `follows` semantics.

Adapter lifecycle authorities, C0.2/C0.3/C0.4 lifecycle authorities, C0.1 historical validation, release closure evidence and the global roadmap transition authority consume this structural policy. Existing domain evidence DTO types remain in place as compatibility wrappers, preserving constructor defaults and JVM type identity while delegating structural validity and YAML parsing. Their item-specific state machines remain separate. This removes duplicated evidence policy without making a generic lifecycle engine the new hidden owner.

## Consolidation 2: blocked adapter diagnostic projection

`AdapterDiagnosticReconciliation` owns only the mechanical mutation that converts already-produced compatibility issues into a blocked diagnostic manifest:

- compatibility becomes `UNSUPPORTED`;
- execution is disabled;
- readiness evidence remains explicitly available;
- domain-produced compatibility issues are preserved without duplication;
- equivalent target mapping notes are emitted;
- domain metadata is retained.

It does **not** decide whether a control, continuity or trigger requirement is satisfied. It cannot derive requirements, inspect target support, choose an evidence status or construct a domain-specific issue. Those responsibilities remain in their existing authorities.

This boundary matters because a helper that accepted policy callbacks or generic requirement objects would technically reduce duplication while quietly creating a second adapter-policy framework. AR0.1 explicitly avoids that direction.

## Deliberately retained independent authorities

### Lifecycle authorities

A0.x, A1.0 and C0.x lifecycle authorities remain independent. They share the representation of CI evidence but own different historical contracts. Parameterizing all of them into one universal state machine would obscure which frozen predecessor evidence authorizes which transition and would make future review depend on configuration rather than type-local policy.

### Adapter control, continuity and trigger policy

The three domains remain separate because their invariants are genuinely different:

- controls preserve authored governance/safety requirements and their scope;
- continuity preserves value/workspace/state transfer and lifetime semantics between semantic endpoints;
- triggers preserve authored trigger family, timing/event identity, workflow scope and parameters.

Only their post-decision diagnostic projection is common.

### Semantic, identity and observation authorities

Canonical meaning, identity assignment, semantic observation and semantic-equivalence evidence remain separate owners. They sit on different sides of important trust boundaries. Combining them would make derivation, identity and independent verification mutually self-certifying.

## Future maintenance rule

A future `*Authority` addition is acceptable when the catalog can answer four concrete questions: what invariant does it own, what inputs may influence that invariant, what output does it authorize, and which production component needs the decision. If those answers duplicate an existing owner, the design should consolidate before the new authority lands.

Conversely, the catalog is not permission to preserve a useless class forever. If a type becomes forwarding-only, its entry will show callers without an independent invariant; AR0.1's rule is to remove it or document the concrete orchestration/trust boundary that justifies it.

## Scope exclusions

AR0.1 does not change:

- Core semantic meaning;
- adapter support claims;
- target syntax or renderer output;
- conformance outcomes or frozen check inventories;
- previously recorded lifecycle evidence.

Completion remains a separate lifecycle act. This implementation must first pass local validation and Flow CI; a later, distinct Flow CI boundary is required before AR0.1 can be marked complete.
