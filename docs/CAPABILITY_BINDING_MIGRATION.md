# A0.3 Capability Binding Migration

## Purpose

A0.3 migrates explicit canonical capability bindings onto one target-neutral resolution contract and one independent adapter certification authority.

Canonical meaning remains the source of semantic parameters and semantic effects. A module action may implement a capability only when the author explicitly selects it through `uses: module.action` and selects a compatible declared system through `params.system`.

A registry never chooses a preferred implementation. Inventory order, target support, module presence and action count are not selection evidence.

## Runtime binding boundary

`IntentBindingContractAuthority` derives a binding contract from:

- the selected canonical capability;
- the explicitly selected module action;
- the action input contract;
- the action target system types;
- the canonical capability parameter contract.

The derived contract partitions canonical semantic parameters into:

- mapped semantic parameters represented by the action;
- explicitly unsupported semantic parameters;
- binding-only action parameters that do not become canonical meaning.

`CanonicalIntentMeaningAuthority` validates the selected action and system, resolves authored values and descriptor defaults once, and records parameter provenance as `SEMANTIC`, `BINDING` or `DEFAULT`.

`IntentToAstPlanner` consumes that resolved parameter set. It does not re-read the action descriptor, reapply defaults or reinterpret unsupported parameters. This removes the earlier dual decision path between validation and AST lowering.

## Canonical effect preservation

Binding evidence declares `PRESERVE_CANONICAL` as its only accepted effect policy.

Canonical effects are derived from the canonical capability before implementation binding. Module effects are concrete adapter evidence used by later planning and safety contracts, but they cannot replace, narrow or enrich canonical semantic effects.

The reference `checkout-build-image` flow proves that canonical capability, canonical effects, source step identity, selected module, selected action and selected system survive Intent → AST → ExecutionPlan lowering.

## Adapter-owned certification manifest

The original A0.3/C0.4 evidence remains frozen at `adapters/bindings/builtin-capability-bindings.yaml` version `1.0`. Post-C1 SI-03 current evidence lives at `adapters/bindings/builtin-capability-bindings-v1.1.yaml`; the exact migration is documented in `docs/CANONICAL_TECHNOLOGY_NEUTRALITY.md` and is machine-checked rather than rewriting the historical file.

The current binding document contains exactly one record for every built-in `implements` claim.

Each record declares:

- exact capability, module and action identity;
- binding class;
- exact compatible system types;
- mapped canonical parameters;
- unsupported canonical parameters with reasons;
- binding-only parameters;
- canonical effect policy;
- repository evidence;
- explicit limitations.

The strict loader rejects unknown or missing fields, malformed enums, duplicate list entries, blank evidence and blank limitations.

`AdapterCapabilityBindingAuthority` compares every record with the target-neutral derived runtime contract and the canonical module registry. It rejects:

- missing or duplicate evidence records;
- records without a matching `implements` claim;
- mismatched system types;
- incomplete semantic parameter partitions;
- unsupported universally required parameters;
- undeclared binding-only inputs;
- absent action effect evidence;
- unresolved or self-referential evidence.

The adapter manifest certifies the built-in distribution. It does not become Core semantic authority and Core packages do not import it.

## Honest binding classifications

### Concrete implementations

The built-in concrete binding records are:

- `git.checkout#CHECKOUT`;
- `docker.build#BUILD_IMAGE`;
- `docker.push#PUSH_IMAGE`;
- `argocd.sync#DEPLOY`;
- `helm.upgrade#DEPLOY`;
- `kubernetes.deploy#DEPLOY`;
- `notify.send#NOTIFY`;
- `notify.email#NOTIFY`;
- `rest.call#CALL_API`.

### Semantic fallback

`standard.rollback#ROLLBACK` is classified as `SEMANTIC_FALLBACK`. It preserves target-neutral rollback intent and does not certify native rollback implementation for any target.

### Intentional demotion

`argocd.sync` no longer claims canonical `SYNC`.

Canonical `SYNC` requires `source` and `destination`. The current Argo CD action accepts an application identity and sync options but cannot represent those required semantics. Keeping the claim would certify a discarded source/destination contract, so A0.3 removes it instead of inventing a mapping.

## Fail-closed behavior

An explicit binding remains invalid when:

- the module or action does not exist;
- the action does not implement the requested capability;
- no system is selected;
- the selected system is unknown or incompatible;
- an action-required binding parameter is absent;
- a supplied canonical parameter is unsupported;
- a supplied binding parameter is undeclared.

An intent step without `uses` remains `UNBOUND` and lowers to target-neutral semantic work. It is never silently attached to the only, first or apparently compatible implementation in the registry.

## Conformance ownership

A0.3 adds five checks to adapter inventory `1.2` after the frozen Core semantic closure boundary:

- lifecycle integrity;
- binding evidence integrity;
- runtime binding authority;
- semantic effect and provenance preservation;
- unresolved and unsupported polarity.

The existing `AdapterStreamConformanceRunner` remains the single complete post-Core adapter composition authority. During final review, a newly introduced parallel complete-stream runner was removed and A0.3 was attached to the existing composition instead. The committed inventory order therefore has one owner rather than two classes that could drift independently.

## Lifecycle and validation

A0.3 completion requires two separate evidence boundaries.

The implementation boundary is Flow CI #2303, run `30354834937`, which passed:

- exact implementation head `f475ae8317122b986dec23a3d36bb8df05a31db3`;
- synthetic merge candidate `afdfa505e96ba00eecf42a94d9b2e1ebe7059ec8`;
- Flow Agent tooling and metadata structure;
- complete compilation and tests;
- the frozen Core closure sequence;
- adapter inventory `1.2` with every A0.1, A0.2 and A0.3 check.

The work package records that implementation evidence structurally. A0.3 is `completed` and A0.4 is `next`, but no A0.4 work package or implementation is included. The completion metadata remains external exact-head CI evidence until its exact completion head and synthetic merge candidate pass independently.

## Non-goals

A0.3 does not:

- add automatic implementation selection;
- add a renderer, provider or runtime executor;
- add target-specific public syntax;
- redefine a canonical capability or parameter;
- derive canonical effects from module effects;
- claim artifact rendering or continuity satisfaction;
- modify the frozen 91-check Core pre-closure inventory;
- change package, public standard or artifact contract versions.
