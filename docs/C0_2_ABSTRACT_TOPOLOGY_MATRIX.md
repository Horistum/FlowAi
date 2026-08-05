# C0.2 Abstract Topology Matrix

## Purpose

C0.2 certifies the existing target-neutral execution-topology contract against synthetic profiles after Jenkins and the bounded GitHub Actions reference have both demonstrated executable behavior.

The matrix is conformance evidence. It does not define new Core meaning, promote adapter support, or establish semantic equivalence. Equivalence remains C0.3 scope.

## Existing topology vocabulary

C0.2 consumes the closed topology vocabulary already owned by Core:

| Dimension | Kinds |
|---|---|
| Isolation | workflow scope, branch isolation, attempt isolation |
| Lifetime | workflow lifetime, suspend/resume |
| Persistence | ephemeral workspace, durable state |
| Propagation | value, workspace, state and failure propagation |

Adding or reordering a topology kind is not a C0.2 operation. Such a change would require a separately justified universal contract change.

## Matrix fixtures

`conformance/topology/abstract-topology-matrix.yaml` declares one fixture for each independent plan shape:

1. sequential work,
2. parallel branches,
3. retry attempts,
4. manual approval,
5. protected work with an error handler,
6. value continuity,
7. workspace continuity,
8. durable state continuity.

The declared kinds are compared with kinds derived from real `ExecutionPlan` instances by `PlanningTopologyAuthority`. The YAML document cannot add a requirement, hide one produced by the planning authority, or invent an alternative canonical ordering.

## Synthetic polarity

Each required topology kind is evaluated through `ExecutionTopologyMatchingAuthority` with the following mutations:

| Mutation | Expected evidence | Expected decision |
|---|---|---|
| missing | `UNKNOWN` | `BLOCKED` |
| partial | `DEGRADED` | `DEGRADED` |
| unsupported | `UNSATISFIED` | `BLOCKED` |
| unknown | `UNKNOWN` | `BLOCKED` |
| contradictory | `CONTRADICTORY` | `BLOCKED` |

A fully supported profile must produce `MATCHED`. Mutating a topology kind that the plan does not require must not change the decision. This proves both enforcement polarity and requirement locality.

A degraded topology assessment remains non-executable at the materialization boundary because provider invocation requires a fully matched assessment.

## Target independence

Every fixture is rebuilt with different module, action and target labels. The derived topology requirements must remain identical.

The same requirements are also assessed against profiles with different synthetic target identities. Target identity is deliberately excluded from the normalized comparison. A target name, action inventory, job count or renderer shape therefore cannot become topology evidence.

## Concrete falsification inputs

C0.2 keeps two executable references live:

- Jenkins `checkout-build-image`, whose generic topology profile matches the plan.
- GitHub Actions `checkout-build-image`, whose generic topology profile remains blocked while the bounded A1.0 promotion produces an executable artifact.

This asymmetry is intentional. It demonstrates that a plan-specific adapter promotion does not rewrite the authored generic topology profile. The concrete platforms can falsify assumptions, but neither platform defines universal topology meaning.

## Evidence isolation

C0.2 has its own inventory at `conformance/topology/c0.2-check-inventory.yaml` and runs after C0.1 evidence.

The following historical inventories remain immutable:

- Core pre-closure inventory,
- A0 adapter inventory,
- A1.0 inventory,
- C0.1 bounded-domain inventory.

C0.2 checks are rejected if they appear in any of those inventories.

## Lifecycle

C0.2 supports three valid phases:

1. `IMPLEMENTING`: no authored validation evidence,
2. `VALIDATING`: one passed exact-head and synthetic merge-candidate implementation boundary,
3. `COMPLETED`: a distinct later completion boundary and an adjacent handoff to C0.3.

Implementation and completion boundaries must use different workflow runs, run identifiers, exact heads and synthetic merge candidates. One green build cannot impersonate two lifecycle boundaries, despite the obvious administrative convenience of pretending otherwise.

## Validation evidence

### Implementation boundary

Flow CI #2674, run `30912265199`:

- exact head `907abdad2b48862b8ed8bd37eece118127298b36`,
- synthetic merge candidate `5569196f60e305b8829e0bea6d63ea169fb0d485`,
- full tests and conformance passed on both revisions.

### Completion boundary

Flow CI #2679, run `30913933725`:

- exact head `4edb2b1440966bdc794d4f001021367654c8b4e7`,
- synthetic merge candidate `2ec62d58b5f7e1944e11c8c31354cdf0366770a7`,
- full tests and conformance passed on both revisions.

The completion boundary includes the corrected global ownership model: the global transition authority owns stream focus, while C0.2 implementation and completion evidence remains owned by the C0.2 lifecycle authority.
