# Real-World Pipeline Survey

## Decision

Flow needs an independent real-world validation corpus beside its authored reference scenarios.

The current `checkout-build-image` proof remains valuable, but it cannot establish general usefulness by itself. A scenario designed while the architecture is being created is vulnerable to reciprocal bias: the model and the example can accidentally validate each other. This survey therefore records external workflows whose authors did not know Flow and had no reason to make their intent convenient for it.

This change is a research baseline. It does not activate C0.1, does not start or complete A0.5, does not add a Core capability and does not promote an adapter support class.

## Roadmap placement

A0.5 already means **Continuity Satisfaction Proof**. The real-world corpus does not replace that item.

The relationship is deliberately one-way:

1. the survey identifies real continuity and representability demands;
2. A0.5 consumes the subset involving value, artifact, workspace, mutable-state and durable-state transfer;
3. C0.1 later owns the broader bounded domain corpus;
4. neither stream may redefine frozen Core meaning to make an external example fit.

## Source classes

The source catalog distinguishes three classes:

- `production-workflow`: a workflow used by an established project;
- `official-example`: an executable example maintained by the platform owner;
- `official-semantic-reference`: platform documentation used to identify semantics, but not accepted as execution proof.

Every admitted source is pinned to an immutable revision. Documentation-only and licensing-boundary cases remain `screened`; they are not quietly counted as completed evidence.

## Initial source coverage

The baseline includes GitHub Actions, Buildkite, Argo Workflows, Tekton, GitLab CI, CircleCI and Azure DevOps material.

The strongest initial production examples are:

- Argo CD release: reusable workflows, multi-architecture image production, digest propagation, OIDC, signing, provenance and SBOM;
- Helm release: tag and main-branch release paths, cross-builds, checksums and separate canary publication;
- Prometheus CI: large parallel fan-out, output-derived matrices, artifact restoration and conditional cross-platform builds.

The official examples add deliberately awkward semantics:

- Buildkite runtime pipeline generation;
- Buildkite artifact transfer;
- typed human release input;
- Argo DAG fan-out/fan-in;
- Argo enhanced dependency outcomes;
- Argo artifact passing.

## Scenario portfolio

The catalog contains 44 scenarios:

- 12 common pipeline cases;
- 12 production delivery cases;
- 12 advanced or atypical cases;
- 8 negative/adversarial cases.

The portfolio is intentionally not a popularity contest. Duplicate syntax is less valuable than distinct semantic pressure.

## High-value findings for current Flow

### 1. Continuity is the first real pressure point

External workflows frequently distinguish:

- an ordering dependency;
- a scalar or structured output;
- a packaged artifact;
- a shared workspace;
- durable or mutable external state.

Flow already models `ORDERING`, `VALUE`, `WORKSPACE` and `STATE`. The corpus must now prove whether adapters satisfy those relations rather than relying on registry labels.

Artifact continuity is tracked as a corpus-level observation because platforms often expose it as a specialized transfer mechanism. During Flow evaluation it must be mapped explicitly to an existing universal relation and artifact contract, not smuggled in as a fifth dependency kind without architectural proof.

### 2. Runtime plan construction is not an ordinary loop

Buildkite's dynamic pipeline example generates and uploads new pipeline structure during execution. Treating this as a normal Flow loop would falsify the planning boundary. The baseline expectation is `UNSUPPORTED_DYNAMIC_CONSTRUCTION` until Flow has an explicit, target-neutral plan-composition contract.

### 3. Human gates can also produce typed data

The Buildkite block example is more than approval. It collects release name, notes and release type, then passes them to later work. Flow must distinguish:

- approval authority;
- typed human input;
- value continuity;
- downstream release behavior.

Collapsing all four into a boolean approval would preserve syntax while discarding intent, an unfortunately common automation tradition.

### 4. Output-driven matrices cross the static/dynamic boundary

Prometheus first computes the active LTS versions, then expands a test matrix from that output. The resulting topology is data-dependent but still bounded by a declared matrix consumer. This case should not automatically share the same classification as arbitrary runtime pipeline generation.

### 5. Build-once and promotion require identity preservation

Release workflows repeatedly propagate digests, checksums, hashes or named artifacts. A target that rebuilds at each environment can preserve task names while violating the author's core invariant. The corpus therefore measures artifact identity separately from mere publish success.

## Admission sequence

Each case advances through these states:

1. source discovered;
2. exact source and license boundary recorded;
3. author intent reconstructed;
4. semantic requirements and invariants written;
5. Flow canonical intent authored;
6. ExecutionPlan generated and inspected;
7. target assessments generated;
8. negative mutations executed;
9. result accepted only after independent evidence.

The committed baseline stops before steps 3 through 9 for most cases. That limitation is recorded rather than decorated with green badges.

## Recommended execution order

The first implementation batch should be continuity-led because it directly supports A0.5:

1. C06 artifact transfer between jobs;
2. A11 output-driven downstream matrix;
3. C02 parallel fan-out/fan-in with no data transfer;
4. P04 build-once promote-many;
5. A06 typed human input;
6. N01 and N07 missing-result diagnostics;
7. N08 parallelism loss.

This order separates ordering, value, artifact and state requirements before tackling broad rendering.

## Non-goals

This baseline does not:

- translate vendor YAML mechanically into Flow;
- copy external workflow bodies into the repository;
- certify any target as executable;
- change roadmap status;
- change package, public standard or artifact contract versions;
- infer universal semantics from a single platform feature;
- make unsupported workflows pass through notes, shell text or silent fallback.
