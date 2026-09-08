# C1.0 Operational Domain Adequacy

## Decision

C1.0 reactivates the conformance stream after the completed AR0.1 architecture boundary. Its purpose is not another architecture refactor. The next material risk is evidence concentration: the executable real-world baseline is intentionally bounded and remains dominated by software-delivery cases, while Flow's stated automation scope also includes backup and restore operations.

C1.0 therefore introduces a **separate post-architecture operational corpus**. It does not extend the closed C0.1 `RealWorldDomain` vocabulary. Historical C0.1 evidence remains an immutable three-domain claim.

## Universal invariant

External operational sources are falsification inputs, never semantic authorities.

A source may prove that Flow loses or cannot represent a relevant operational property. It may not create a new universal capability, weaken an existing contract, or turn concrete platform mechanics into Core meaning. Semantic representability also does not imply target support or executable rendering.

## First bounded domain: data protection

The first C1.0 domain is `data-protection` with two required existing canonical capabilities:

- `BACKUP`
- `RESTORE`

The bounded scope is deliberate. It is large enough to move evidence outside software delivery, but small enough that every admitted case can carry immutable provenance, an explicit canonical reconstruction, exact plan expectations and negative mutation polarity.

## Source evidence

The initial evidence uses the official Velero backup and restore references pinned to revision `7346ec527c99c156bcbbb4a76d83c31b2370f504` from `velero-io/velero`, licensed Apache-2.0.

Velero is not a Flow semantic authority and C1.0 does not claim a Velero target adapter. Its documentation is useful because it independently exposes concrete backup and restore operations, recovery-point behavior and validation constraints that can challenge the existing target-neutral model.

Only a minimal source excerpt is retained in each case package. Provenance records identify the complete immutable upstream document and license boundary.

## Execution boundary

C1.0 does not implement a second intent interpreter. Operational cases reuse the same production evaluation path already exercised by the C0.1 corpus:

1. strict Intent YAML loading;
2. capability validation;
3. Intent-to-AST lowering;
4. Flow validation;
5. production planning;
6. exact task and dependency comparison.

The shared evaluator accepts an evidence-check namespace so C1.0 evidence remains independently named. Existing C0.1 behavior retains its original namespace by default.

## Positive and negative evidence

### DP01 backup

The canonical baseline preserves an explicit `BACKUP` operation, emits a named `backup_artifact` value and verifies that value. Its mutation removes the producer while leaving the consumer intact. The mutation must fail before planning with the exact missing-value-producer diagnostic.

### DP02 restore

The canonical baseline preserves an explicit `RESTORE` operation with an authored recovery point, emits named `restored_state` and verifies that state. Its mutation removes the producer while leaving the consumer intact. It must fail with the same exact missing-value-producer diagnostic.

A case is useful evidence only when the observed baseline is `REPRESENTABLE` and its mutation is observed as `REJECTED`. Merely matching an expected file is insufficient.

## Closed C0.1 boundary

C1.0 contains an explicit preservation check for C0.1:

- the C0.1 manifest keeps exactly its original three domains;
- C1.0 cases are absent from the C0.1 case inventory;
- C1.0 check identifiers are absent from the C0.1 conformance inventory.

This prevents later maintenance from quietly rewriting the historical meaning of C0.1 while appearing to "just add coverage".

## Lifecycle

C1.0 activation evidence is exactly the distinct AR0.1 completion boundary, Flow CI #2809 / run `31202921728`.

The lifecycle remains fail-closed:

- `IMPLEMENTING`: active work package, no authored implementation or completion boundary;
- `VALIDATING`: a later distinct implementation boundary is recorded;
- `COMPLETED`: a still later distinct completion boundary exists and the conformance stream closes at C1.0.

No completion metadata is authored in the implementation change itself.

## Explicit non-goals

C1.0 does not:

- add a runtime executor;
- add or change a target adapter;
- promote Velero or Kubernetes into Core semantics;
- expand the C0.1 domain enum;
- change frozen semantic, adapter or C0.1-C0.4 inventories, or rewrite recorded AR0.1 lifecycle evidence; the live authority-responsibility catalog remains a required maintenance contract;
- claim broad operational coverage from two cases.

Secret rotation, incident response, runbook execution, certificate renewal and database migration remain candidate future evidence domains. They require separate source review and an explicit later roadmap decision rather than being smuggled into C1.0 scope.
