# Real-World Corpus Methodology

## Purpose

The corpus measures whether Flow preserves externally authored automation intent across normalization, planning and target assessment.

It is not a syntax-transpilation benchmark. Textual similarity to the source pipeline is irrelevant unless it corresponds to preserved observable meaning.

## Evaluation pipeline

```text
immutable external source
    -> source behavior inventory
    -> reconstructed author intent
    -> universal requirements and invariants
    -> Flow canonical intent
    -> Flow AST
    -> ExecutionPlan
    -> explicit target selection
    -> target assessment or materialization
    -> semantic comparison and negative mutations
```

No stage may use the output of the stage it is validating as its own expected evidence.

## Source admission

An admitted source must record:

1. repository identity;
2. immutable commit revision;
3. exact path;
4. source class;
5. license evidence;
6. semantic reason for inclusion.

A mutable branch, a marketing page or a hand-written paraphrase is insufficient.

An official semantic reference may remain `screened`. It can justify a research hypothesis, but cannot certify executable behavior.

## Intent reconstruction

The source must first be described without vendor syntax.

For every case, record:

- desired outcome;
- actors and external systems;
- triggers;
- dependency graph;
- values and artifacts transferred;
- workspace and state lifetime;
- controls, approvals, retry, timeout and compensation;
- security and identity assumptions;
- failure and cleanup behavior;
- invariants that must remain true.

Ambiguity is an allowed result. The evaluator must not invent author intent simply because Flow requires a field.

## Continuity classification

Ordering only proves that a predecessor completes before a consumer may run.

Continuity must be classified separately:

- `value`: scalar or structured data consumed by later work;
- `artifact`: a named, immutable or packaged output transferred through an artifact mechanism;
- `workspace`: files shared through a workspace lifetime;
- `state`: mutable or durable state whose identity survives task boundaries.

`artifact` is a corpus observation, not automatically a new Core dependency kind. The Flow mapping must state whether the artifact is represented by VALUE, WORKSPACE, STATE and the artifact contract, and why.

## Result vocabulary

- `SUPPORTED`: universal meaning is represented and the selected target has complete evidence.
- `SUPPORTED_WITH_BINDING`: meaning is represented but a concrete, explicit adapter binding is required.
- `SEMANTIC_ONLY`: Flow preserves intent and requirements but no selected target has executable evidence.
- `BLOCKED_BY_TARGET_CAPABILITY`: Flow meaning is valid, but the selected target cannot satisfy a required invariant.
- `AMBIGUOUS_SOURCE_INTENT`: the source does not establish one safe interpretation.
- `UNSUPPORTED_DYNAMIC_CONSTRUCTION`: runtime creation of workflow structure exceeds the current planning contract.
- `INVALID_SOURCE_PIPELINE`: the reconstructed source contains a missing, contradictory or invalid dependency.

A baseline expectation is only a hypothesis. It becomes a result only after committed evidence is produced.

## Scoring dimensions

Each completed case receives an evidence-backed assessment in seven dimensions:

1. **Intent fidelity**: desired outcome and source identity remain present.
2. **Constraint fidelity**: policies, permissions, environment and resource constraints remain present.
3. **Failure fidelity**: failure propagation, retry, cleanup and compensation remain equivalent.
4. **Continuity fidelity**: values, artifacts, workspaces and state reach the correct consumers with the required identity and lifetime.
5. **Portability honesty**: unsupported target behavior is blocked rather than approximated.
6. **Diagnostic precision**: failure identifies the exact relation, source, consumer and missing evidence.
7. **Target neutrality**: no vendor object becomes universal Core meaning.

A numeric aggregate may be reported for observation, but cannot override a blocking invariant.

## Case package

A completed case should contain:

```text
case.yaml
source-observations.md
canonical.intent.yaml
expected/execution-plan.json
expected/target-assessments.yaml
mutations/
evidence/
```

`case.yaml` owns identity and required invariants. Generated artifacts never rewrite that authority.

## Negative mutations

Each positive case should produce mutations that remove or corrupt one important property:

- remove a producer;
- rename a consumed output;
- change artifact identity;
- replace workspace transfer with ordering only;
- shorten state lifetime;
- remove approval;
- change fail-fast behavior;
- serialize required parallel branches;
- detach cleanup from protected work;
- replace an immutable revision with a mutable source.

A mutation passes only when Flow rejects it or produces the exact declared review-only result.

## Core-change gate

An external feature may lead to a Core proposal only when:

1. it appears across more than one implementation family;
2. it describes author intent rather than target syntax;
3. bindings cannot preserve it without semantic loss;
4. the missing concept causes an observable invariant to be lost;
5. its dependencies, effects, failure semantics and diagnostics can be specified independently;
6. negative examples prove its boundary.

Until then, the corpus records the gap. It does not reward architectural impatience with a new enum.

## A0.5 use

A0.5 should select the continuity-focused cases and prove adapter behavior against existing `ORDERING`, `VALUE`, `WORKSPACE` and `STATE` relations.

The corpus must not allow:

- target registry labels to certify themselves;
- action support to substitute for producer-to-consumer behavior;
- artifact upload support to imply correct artifact identity;
- a shared filesystem to imply durable state;
- successful ordering to imply value or workspace transfer.

## C0.1 use

C0.1 may later activate the broader three-domain corpus after its lifecycle and evidence boundaries are defined.

This baseline is input to that work. It is not completion evidence for it.
