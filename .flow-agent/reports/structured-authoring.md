# Structured authoring and conditional fidelity

Baseline: merged PR #260, `81ecc2c91220a6a6121142cbb2d307f8ee90b277`.
Work package: [AI-A3-S2.1](../work-packages/structured-authoring.yaml).
The maintainer requested implementation of the reviewed design in draft PR #261.

## Result

Intent 3.0 has ordered operation/branch/value/merge bodies, lowered to the existing
AST and canonical graph. Intent 2.0 keeps its default and flat ordering behavior.
Requirements 3 distinguishes conditional restrictions from required per-path work
and checks actual canonical joined-value provenance. The external host advertises
the selected contracts while retaining immutable reviewer-owned requirements.

The implementation preserves branch contexts through approval and backup coverage,
rejects forward/sibling dependencies, checks non-null Boolean predicate contracts,
and keeps canonical merge coverage and type constraints authoritative. A whole-body
lowering receipt compares source structure with actual projected nodes. Reviewed
AI snapshots deep-copy recursive bodies and bind integrity to the compiled snapshot.

Two integration gaps were resolved through the existing compiler authorities:

- Local reference values previously registered a producer without recording their
  input VALUE edge. `FlowPlanner` now records these dependencies, so alias origin
  can be checked rather than guessed. Flow Source receives the same correction.
- The legacy flat Intent topology overlay added different workflow requirements
  from otherwise identical Flow Source. Structured Intent instead uses the existing
  plan topology authority on actual nodes/edges. Equivalent structured source and
  Intent joins now have the same semantic graph digest without weakening the digest.
  Explicit structured source-version provenance and mandatory body/version receipts
  also let materialization re-derive the same obligations without reinstating legacy
  flat topology. Removing those receipts fails validation.

## Evidence and limitations

The four manual fixtures cover infrastructure choice, maintenance gating, nested
recovery source selection and a separate serial/nested generic composition. The
installed product verifier includes five adverse mutations and is part of both
existing full-validation CI paths. The original twelve-case corpus remains frozen.

Conditional fidelity certifies public Boolean input predicates. Typed operation
result/local-alias predicates can compile but currently require independent review;
repeated predicates need correlation analysis and also require review. Unknown
selectors, transforms and unsupported control forms are not treated as satisfied.
Global legacy failure actions and implicit conditional approval policies are rejected
in 3.0. Resource observations, ownership, runtime postconditions, retries, recovery,
concrete cloud execution and live model generalization remain unassessed.

AI-A3, AR-06/AR-07, F-16/F-20 remain open and EF-09 remains paused. The next S2 scope is
operation outcomes and resource boundaries, not another scenario-specific adapter.

## Validation

- Offline JVM regression: 2,464 tests, zero failures/errors/skips. Both installed
  distributions build successfully with JDK 25.
- Python tooling: 339 tests pass. Agent structure validation and whitespace checks
  pass; new documentation links resolve.
- Installed conformance: 302 checks pass.
- Installed host proof: 36 frozen corpus runs / 49 attempts, five rejected
  clarification mutations, six investigation cases, ten system cases and eleven
  structured cases pass. The latter comprise four baselines, five adverse mutations
  and two target assessments.
- GitHub Actions refuses executable output and retains diagnostic review evidence
  (`review_only`, exit 3); Azure DevOps retains the accepted plan and blocks target
  materialization (`blocked`, exit 4). Both retain satisfied independent requirements.
  Target selection is not promoted to executable support.

The final local regression was rerun after the source-version/materialization
correction. Exact-head and merge-candidate CI, including physical source-deletion
isolation, remain required on the final published revision; earlier draft results
cannot satisfy that requirement. No live model or external infrastructure ran.
