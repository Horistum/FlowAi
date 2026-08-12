# Flow project direction after C1.0

## Status and purpose

This document records the strategic direction established after C1.0 Operational Domain Adequacy. C1.0 is now completed; actual successor selection is owned by the global roadmap and semantic-integrity lifecycle metadata, not by this document.

The purpose is to prevent Flow from drifting into a system that proves its own governance more convincingly than it proves useful, portable automation semantics. The ordered track remains strategic input; each active item still requires an explicit global roadmap transition and bounded work package.

## Architectural position

Flow remains a target-neutral language and standardization model for automation intent. It is not a runtime, an SDK-first framework or a second implementation-specific pipeline DSL.

The next phase of the project should optimize for three things, in this order:

1. semantic correctness;
2. external falsifiability across heterogeneous automation domains;
3. independently proven executable portability.

Governance is supporting infrastructure. It is not a product outcome by itself.

## Track 1: Semantic integrity correction

This is the first candidate track after C1.0 because the project-wide architecture audit exposed defects that can produce formally valid evidence while weakening or changing authored meaning.

### 1.1 Scope safety and control evidence to the protected operation

Canonical safety evidence must no longer be satisfied by unrelated evidence elsewhere in the document.

Required corrections:

- a BACKUP control must be associated with the specific migration, destructive change or protected resource it is intended to safeguard;
- rollback evidence must be scoped to the operation for which rollback is required;
- approval and review evidence must be related to the protected operation by explicit semantic or graph scope;
- a notification is not external review;
- merely declaring an optional change-ticket input does not prove that a change ticket exists;
- generic safety evidence must not satisfy a concrete control obligation without an explicit relationship.

The stronger task-scoped dependency behavior already used by planning controls is the minimum quality bar for canonical control evidence.

### 1.2 Preserve the authored dependency graph exactly

Topological validity is not semantic equivalence.

The lowering path must preserve authored ordering without silently inserting a dependency between independent siblings. If an implementation requires additional technical ordering, that ordering must be explicit, typed and separately evidenced rather than being inserted into the canonical graph as if the author had requested it.

Lowering evidence must prove both directions:

- every authored ordering edge survives;
- no additional semantic ordering edge is invented.

The existing real-world N08 parallelism-loss case remains important evidence and should be retained as a permanent regression boundary.

### 1.3 Remove implementation technology from canonical meaning

Target-neutral capability contracts must not treat Docker, Kubernetes, Helm, Argo CD or similar concrete products as universal semantic definitions.

Examples of required cleanup include:

- replace canonical Docker-specific BUILD_IMAGE vocabulary with implementation-neutral container-image build semantics;
- move Dockerfile and builder-specific concerns into binding or adapter evidence unless the authored intent explicitly selects them;
- ensure standard intent catalog examples do not accidentally define implementation products as the semantic contract;
- keep implementation system hints descriptive and non-authoritative where they remain useful.

### 1.4 Make canonical execution-plan semantics explicit

A canonical public node kind must not change because a module is named `notify`, an action string is `rollback`, or a resource name happens to contain `artifact`.

Node semantic kind should be derived from explicit typed canonical meaning. Implementation module/action/resource naming may provide adapter evidence, but it must not define the public target-neutral contract.

### 1.5 Re-evaluate the effect model under operational evidence

C1.0 intentionally introduces BACKUP and RESTORE pressure. Those operations should be allowed to falsify the current effect taxonomy.

The project must determine whether the current domain/operation/state model can represent at least:

- recovery-point identity;
- source and destination state relationship;
- consistency boundary;
- retention or lifetime;
- recoverability and restore intent;
- state replacement versus state creation.

If it cannot, the model should change. Calling backup and restore generic data transformation merely to keep the current enum stable is not an acceptable outcome.

### 1.6 Eliminate remaining silent authored-value coercion

Parser defaults apply only when the author omitted a value.

Malformed authored values, including invalid boolean literals such as an invalid `parallel failFast` value, must fail with explicit diagnostics rather than silently becoming a default value.

Property-based parser tests should be preferred for these boundaries because hand-picked happy-path examples are unusually talented at avoiding the bug they were supposed to find.

### 1.7 Align public schemas with production acceptance

The project must explicitly decide whether each published schema is:

- a syntactic interchange schema; or
- the actual public validity contract.

If it is the validity contract, schema-valid input and production-loader-valid input must agree. Arbitrary strings accepted by a schema but rejected by strict production enums create a false interoperability promise.

### 1.8 Replace string heuristics with typed policy and lifetime semantics

Policy classification must not infer retention from unrelated fragments such as an environment guard.

State continuity must distinguish the lifetime actually required by the authored semantics. A workflow-local mutable state transfer is not automatically the same requirement as durable state that must survive executions or restarts.

## Track 2: External falsification expansion

After semantic integrity corrections pass, Flow should deliberately seek automation sources least similar to conventional CI/CD pipelines.

Minimum candidate domains:

- database migration and recovery;
- backup and restore;
- incident remediation;
- certificate lifecycle;
- secret rotation;
- data-pipeline orchestration;
- infrastructure lifecycle;
- human approval and change-control workflows.

Each admitted case should have:

- immutable source provenance;
- an explicit target-neutral authored intent or a documented reason why representation is impossible;
- a production-path canonical baseline;
- at least one negative mutation where an enforceable invariant exists;
- an explicit distinction between semantic representability, target support and executable rendering.

A source that Flow cannot represent is useful evidence. It should create a bounded model gap or correction, not pressure maintainers to weaken validation until the example passes.

## Track 3: Structural architecture boundaries

Flow currently spends substantial code proving dependency directions that the build system could enforce directly.

The preferred future architecture is a multi-module build with explicit dependency direction, for example:

- `flow-core-model`;
- `flow-planner`;
- `flow-adapter-api`;
- target implementation modules such as Jenkins and GitHub Actions;
- `flow-conformance-governance`;
- `flow-cli`.

Exact module names are not frozen by this document. The invariant is more important: implementation-specific modules may depend on frozen neutral contracts, while Core must not depend on adapter, target or conformance implementation code.

After module boundaries exist, lexical governance made redundant by compiler-enforced dependencies should be deleted or consolidated.

A governance budget should apply: adding a new Authority, frozen inventory or report requires an explicit explanation of why an existing owner cannot enforce the invariant and what overlapping mechanism can be removed or consolidated.

This track should also clean up stale documentation and version terminology. Roadmap work-item identifiers should not look like package versions when they are not package versions.

## Track 4: Multi-target executable proof

Flow should not equate a bounded executable scenario with broad target support.

The practical proof target is several independent implementations whose execution models differ enough to falsify portability assumptions. Two strong targets are better than five profile-only adapters. A third target should be added when it contributes a genuinely different execution model.

The executable corpus should move beyond checkout/build-image paths and exercise, where semantically relevant and target-supported:

- branching and independent parallelism;
- artifacts and workspaces;
- explicit secret bindings;
- approvals and protected transitions;
- retry and timeout controls;
- rollback or compensation;
- workflow-local mutable state;
- durable state across executions;
- scheduling and trigger semantics.

Generic support remains UNKNOWN or UNSUPPORTED when only a bounded promotion is proven.

## Track 5: AI intent provider

A stronger AI-assisted intent layer belongs after the canonical model has survived the earlier pressure. It should remain outside Core semantic authority.

Provider output must support:

- explicit uncertainty;
- unresolved bindings;
- clarification requirements;
- provenance of inferred versus authored facts.

The provider must not invent an email channel, Slack, Docker, Kubernetes, a cluster, a secret source or another implementation choice simply because one is convenient.

Heuristic scores should be called match strength unless calibrated evidence supports a probabilistic interpretation.

Every provider-produced intent must pass the same production parser, canonicalization, control, safety, lowering and conformance boundaries as manually authored intent.

## Release-readiness model

Future Flow maturity claims should be separated into at least three axes:

1. semantic maturity: can the canonical model represent and preserve the intent correctly;
2. adapter maturity: does a concrete target have explicit capability, topology, control and continuity evidence;
3. executable maturity: has the exact production materialization been independently executed and validated for the claimed scenario class.

No single `stable` label should silently collapse these three meanings.

## Stop conditions

Development should stop normal roadmap advancement and open a bounded correction when any of the following is true:

- a known P0 semantic defect can authorize unrelated safety evidence;
- lowering changes the authored semantic dependency graph without explicit evidence;
- a concrete implementation product becomes the source of universal Core meaning;
- a public schema certifies input that the declared public production contract rejects, unless the schema is explicitly documented as syntactic-only;
- target support is promoted from representability, registry presence or renderer existence rather than independent evidence;
- new governance is added mainly to certify existing governance instead of protecting a product invariant.

## What does not change yet

This document does not complete C1.0, reopen the frozen Core closure, activate a new stream or select a successor work item.

C1.0 must first complete using a later, distinct exact-head and synthetic merge-candidate validation boundary. Only after that evidence exists may the global roadmap transition authority activate the next bounded work package.
