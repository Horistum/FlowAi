# v0.9.5.7.4 Remove Legacy Shell Generator Fixtures

## Purpose

v0.9.5.7.4 removes the active test paths that still encoded shell-oriented Jenkins or GitHub Actions generation as supported Flow behavior.

The retired generators mapped Flow actions directly to Jenkins steps, GitHub Actions echo steps and runtime command strings. Their assertions contradicted the production architecture, which now requires semantic actions, materialization negotiation, projection evidence and renderer readiness.

## Changes

- Deleted `src/test/kotlin/org/flowlang/generators/JenkinsGenerator.kt`.
- Deleted `src/test/kotlin/org/flowlang/generators/GitHubActionsGenerator.kt`.
- Replaced the monolithic `tests/FlowSpecTests_part2.kt` with responsibility-based language, validation, planner, scenario, module and source-location harness files.
- Removed assertions that treated `sh`, `run: echo`, `kubectl`, Helm or Argo CD command projection as correct behavior.
- Replaced generator smoke coverage with the production `ExecutionPlan -> TargetManifest -> TargetRenderPolicy -> renderer` path.
- Added JUnit regression coverage that verifies the removed source paths cannot return and that all remaining shell examples are blocked consistently across Jenkins, GitHub Actions and Tekton.

## Preserved coverage

This repair does not discard parser, validator or planner coverage merely because some fixtures still express explicit runtime command intent.

The split harness preserves:

- action and control-flow parsing
- document and validation behavior
- planner node and dependency behavior
- canonical example parsing and planning
- expression round-trip and stress coverage
- module descriptor loading and parity
- source-location diagnostics

The distinction is deliberate: parsing and preserving explicit intent is valid; claiming that intent has been materialized into an executable target artifact is not.

## Example classification

The current example corpus still contains `shell.run` in:

- `build-test.flow`
- `complex-devops-flow.flow`
- `deploy-with-approval.flow`
- `hello.flow`

These files remain migration and language fixtures until v0.9.5.7.9 resets the reference scenario corpus. Their command content must remain visible in the manifest for review, but every shell action must have `BLOCKED` materialization and every implemented renderer must report `FAIL_FAST` readiness.

The JUnit test discovers the shell examples from source content and requires the discovered set to match the reviewed classification. A new shell-based example therefore cannot enter the corpus unnoticed.

## Test boundary

The active test suite now proves:

- the legacy generator source files are absent
- the former monolithic shell-generator harness is absent
- language and planner coverage remains present in the split harness
- no active harness assertion protects `sh`, `run: echo` or cluster CLI projection
- explicit command content is not silently removed
- shell actions are blocked across Jenkins, GitHub Actions and Tekton
- blocked work cannot return target syntax

## Next step

v0.9.5.7.5 connects capability compatibility to materialization and projection readiness so a target cannot be reported as supported while required work remains unresolved.
