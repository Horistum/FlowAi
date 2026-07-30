# Real-World Pipeline Survey

## Decision

Flow needs an independent real-world validation corpus beside internally authored reference scenarios. External workflows provide adversarial pressure from authors who had no reason to make their automation convenient for Flow.

PR #100 now contains both the broader survey and an executable vertical baseline. It does not activate C0.1, complete A0.5, add a Core capability or promote an adapter support class.

## Roadmap placement

A0.5 remains **Continuity Satisfaction Proof**. The survey identifies demands, executable cases expose preserved meaning and gaps, A0.5 may consume continuity evidence, and C0.1 may later expand the accepted domain corpus.

## Source portfolio

The catalog covers GitHub Actions, Buildkite, Argo Workflows, Tekton, GitLab CI, CircleCI and Azure DevOps. Its 15 immutable records are deliberately classified rather than presented as one homogeneous production sample:

- 3 production workflows: Argo CD release, Helm release and Prometheus CI;
- 8 official examples: two GitHub Actions starters, three Buildkite examples and three Argo Workflows examples;
- 4 official semantic references: Tekton, GitLab, CircleCI and Azure DevOps documentation.

The six executable cases use one production workflow as their primary fixture, A11 from Prometheus CI. The other five use official examples. Official examples are independent and useful, but they are focused teaching artifacts rather than evidence of production-scale configuration complexity.

The source-class and executable-case counts are declared in the corpus manifest and checked against the source catalog and case provenance.

## Scenario portfolio

The broader catalog contains 44 scenarios: 12 common, 12 production, 12 advanced and 8 negative. Six have complete executable packages and eight mutations. The other 38 remain hypotheses rather than decorative green checks.

## Findings proven by the executable baseline

### Ordering is not parallelism

C02 and N08 detect that current Standard Intent lowering serializes source-ordered sibling steps and refuse to certify the Argo diamond as preserved.

### Artifact identity needs named continuity

C06 proves a producer-to-consumer VALUE relation with channel `package_bundle`. Its ordering-only mutation is rejected because dependency order does not establish artifact identity.

### Typed human input is not a boolean approval

A06 preserves typed release inputs, constrained release type, a distinct approval node and an approval output. Removing the output produces an exact missing-producer diagnostic.

### Bounded output-driven matrices remain unsupported

A11 preserves the `versions` value relation but reports that the current plan has no dynamic matrix topology. The result is `UNSUPPORTED_DYNAMIC_CONSTRUCTION`, not flattened fake success.

### Missing results fail before target selection

N01 rejects an unproduced reference through `REAL_WORLD_MISSING_VALUE_PRODUCER`. No target assessment is attempted.

## Conformance ownership

The corpus checks are post-Core evidence. `ConformanceRunner` builds the frozen pre-closure collection separately and passes only that collection to semantic closure. Adapter certification and real-world checks are composed afterward from their own phases.

A regression test verifies that `real-world-corpus.*` appears in neither the frozen Core inventory nor the adapter inventory and executes only after adapter certification. Closure remains exact and is not weakened by prefix filtering.

## Checks

```text
real-world-corpus.integrity
real-world-corpus.case.c02
real-world-corpus.case.c06
real-world-corpus.case.a06
real-world-corpus.case.a11
real-world-corpus.case.n01
real-world-corpus.case.n08
real-world-corpus.mutations
```

## Non-goals

The corpus does not mechanically translate vendor YAML, copy whole repositories, claim all 44 scenarios are complete, claim vendor examples prove production-scale resilience, certify targets from registry labels, change public versions, add a fifth artifact dependency kind or hide semantic loss behind review notes.
