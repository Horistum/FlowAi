# C0.3 Semantic Equivalence Rules

## Purpose

C0.3 defines when two implementation outcomes preserve the same required observable meaning. It does not compare provider syntax, files, jobs, action names or renderer layout. Those are implementation mechanisms and may differ without changing Flow meaning.

The authority consumes a target-neutral `ExecutionPlan` and derives an explicit observation set before implementation evidence is considered. An implementation can be certified only when every required observation is preserved exactly and no undeclared observation evidence is used to broaden the claim.

## Observation model

C0.3 uses four closed observation kinds:

1. `effect` preserves the canonical effect domain, operation, resource, state transition, external boundary and source capability.
2. `result-identity` preserves the authored result identity associated with the semantic source step.
3. `result-value` preserves the named plan output, its type and its semantic producer.
4. `continuity` preserves the declared `VALUE`, `WORKSPACE` or `STATE` relation, channel, resolution and semantic producer-to-consumer identity.

Implementation-owned module, action and target labels are excluded from observation identity and fingerprints. Stable source identities are preferred over lowered node identifiers. Length-prefixed SHA-256 fingerprints prevent component-boundary ambiguity while retaining deterministic identities.

## Decision polarity

A semantic equivalence decision has only two states:

- `EQUIVALENT`: the requirement set is non-empty and every required observation has exactly one matching `PRESERVED` evidence record.
- `NOT_EQUIVALENT`: one or more observations are missing, weakened, unknown, contradictory, duplicated, fingerprint-mismatched or undeclared.

An empty requirement set never certifies equivalence. Vacuous success would prove only that nothing was checked, an achievement already well represented elsewhere in software engineering.

The mutation matrix independently proves rejection polarity for every required observation:

- missing evidence;
- weakened evidence;
- unknown evidence;
- contradictory evidence.

Evidence for an unknown requirement also blocks certification. C0.3 therefore cannot be expanded silently by implementation-specific claims.

## Implementation independence

Each synthetic fixture is derived twice with different module, action and target labels. The resulting requirement lists must be identical. Evidence references may identify different providers or snapshots, but provider identity cannot change the decision or the observation statuses.

This allows implementations to use different native mechanisms while preserving the same Flow meaning. It deliberately forbids syntax equality, job equality or action-sequence equality from becoming semantic truth.

## Concrete falsification pair

The existing `checkout-build-image` intent is rebuilt through the production path:

`IntentYamlLoader -> IntentToAstPlanner -> FlowPlanner -> ExecutionPlan`

Its required observations are derived before either target snapshot is read. The committed Jenkins and GitHub Actions executable references then act as independent implementation evidence. C0.3 requires:

- both snapshot indexes to pass `ReferenceSnapshotHonesty`;
- each snapshot to contain exactly one executable state for its declared target;
- each snapshot to declare a non-executable semantic `execution-plan.json` artifact;
- both target snapshots to preserve the same target-neutral execution plan;
- both evidence profiles to preserve the complete derived observation set;
- no target token to enter an observation identity or value.

The concrete pair does not promote generic target support and does not redefine Core meaning. It may falsify the equivalence rules, but it cannot author them.

## Evidence isolation

C0.3 owns a separate ordered inventory under `conformance/equivalence/c0.3-check-inventory.yaml` and runs after C0.2. It rejects C0.3 check identifiers from the frozen inventories owned by:

- Core pre-closure conformance;
- A0 adapter conformance;
- A1.0 executable continuity;
- C0.1 bounded-domain evidence;
- C0.2 abstract topology evidence.

No previous inventory is rewritten to make C0.3 appear complete.

## Lifecycle

C0.3 supports three valid phases:

1. `IMPLEMENTING`: C0.2 activation evidence is valid and no C0.3 implementation evidence is authored.
2. `VALIDATING`: one later exact-head and synthetic merge-candidate Flow CI boundary has passed.
3. `COMPLETED`: a distinct, later completion boundary has passed and the adjacent handoff selects C0.4.

Implementation evidence must be later than the C0.2 activation boundary. Completion evidence must be later than and distinct from implementation evidence by run number, run id, exact head and synthetic merge candidate.

## Explicit exclusions

C0.3 does not:

- add or change Core semantic meaning;
- infer meaning from Jenkins, GitHub Actions or any other platform;
- require target syntax, job layout or action identity equality;
- promote adapter capability, topology, control, continuity or rendering support;
- mutate completed Core, adapter, C0.1 or C0.2 inventories;
- treat an executable artifact as proof that every required semantic observation was preserved.
