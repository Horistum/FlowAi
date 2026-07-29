# Real-World Pipeline Representability Baseline

## Status

`BASELINE_ONLY`

This report records the survey and expected pressure points on main commit `a3b8c13783a37ea7b6176eddc4760cae3149a983`.

It is not a Flow execution result, target certification or roadmap transition.

## Corpus inventory

- sources: 15
- admitted immutable sources: 11
- screened semantic references: 4
- scenarios: 44
- admitted scenarios: 21
- screened scenarios: 8
- planned scenarios: 15

## Existing universal boundary

The current ExecutionPlan distinguishes:

- `ORDERING`;
- `VALUE`;
- `WORKSPACE`;
- `STATE`.

That is an appropriate starting model. The baseline found no justification for changing it before behavioral adapter evidence exists.

External workflows do, however, make artifact identity highly visible. The corpus records `artifact` as an observation and requires an explicit mapping to existing continuity plus artifact contracts. It does not silently add another Core dependency kind.

## Current representability hypotheses

### Likely representable with explicit binding

- conventional checkout, build and test;
- static fan-out/fan-in;
- tag-triggered releases;
- static matrices;
- ordinary artifact upload/download;
- OIDC-backed release identity;
- DAG ordering.

### Semantically representable but not yet executable across targets

- build-once promote-many;
- typed human input beyond boolean approval;
- parent/child pipeline composition;
- cross-project continuity;
- cleanup that consumes failed-task results;
- partial-success aggregation;
- durable-state promotion.

### Explicitly outside the current planning contract

- arbitrary code that creates new workflow structure at runtime.

The dynamic-matrix case remains deliberately separate. A bounded matrix derived from a prior value may require a narrower contract than unrestricted pipeline upload.

## A0.5 implications

A0.5 should not begin by adding renderer behavior.

It should first produce independent provider evidence for:

1. value output to consumer input;
2. packaged artifact producer to consumer;
3. shared workspace producer to consumer;
4. mutable state identity and lifetime;
5. durable state identity across execution boundaries;
6. missing producer and missing output diagnostics;
7. prevention of ordering-only false positives.

The first source-backed probes should use:

- Buildkite artifact transfer;
- Argo artifact passing;
- Prometheus output-derived matrix;
- Argo DAG diamond as the ordering-only control;
- Argo CD digest and provenance propagation;
- Buildkite typed release input.

## Adapter-risk observations

- GitHub Actions exposes artifact transfer while workspace propagation is currently unsupported in Flow evidence. A0.5 must prove that those claims remain distinct.
- Tekton exposes both task results and workspaces. Partial labels cannot be promoted without concrete provider composition and behavior tests.
- Jenkins is the executable reference, but its existing success cannot certify every continuity class. In particular, durable and mutable state need identity and lifetime evidence rather than a shared agent assumption.
- Argo Workflows and Azure DevOps remain useful research targets, but profile-level platform knowledge is not composed-provider evidence.

## Honest limitations

No external pipeline has yet been converted into canonical Flow intent in this baseline.

No scenario has an accepted representability result.

No adapter claim changes.

No Core or conformance inventory changes.

The baseline is successful only in the modest sense that the repository now has a reviewable, immutable research input instead of a confident list in a chat window, humanity's least durable storage format.
