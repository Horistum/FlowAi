# Architecture Decision: Add the first target-scoped executable reference scenario

## Status

Accepted

## Decision

Flow commits a new `checkout-build-image` reference scenario and executable snapshot evidence scoped to Jenkins.

The scenario contains two ordered semantic actions:

1. `git.checkout` of a public repository;
2. `docker.build` of the checked-out workspace with `push: false`.

The executable claim is produced only by the real Intent, validation, AST, ExecutionPlan, compatibility, readiness, materialization, Target Manifest and Jenkins renderer pipeline. The committed target artifact is `jenkins.executable.yaml` and every materialization leaf contains a resolved provider-owned native payload.

## Why the proof is target-scoped

An executable action is not automatically an executable multi-step scenario. The target must also preserve the data and ordering relationship between actions.

The Jenkins generator renders the two native actions in one ordered stage and one workspace. That makes checkout output available to the subsequent Docker Pipeline build without inventing an artifact-transfer contract.

GitHub Actions is intentionally excluded. Its current generator creates one job per task, while no explicit workspace or artifact transfer connects checkout to image build. Rendering both native actions and calling the result end-to-end executable would be dishonest.

Tekton is also excluded from this first proof. Although the current tasks expose a shared workspace name, committed reference evidence does not yet include the complete PipelineRun workspace binding and reviewed runtime contract needed for an end-to-end claim.

## Scope

Included:

- one new target-neutral Intent document;
- target-scoped reference snapshot generation through an explicit target set;
- canonical semantic and executable target artifacts;
- behavioral tests and conformance derived from the real pipeline;
- preservation of the existing mixed `build-test-deploy` evidence.

Excluded:

- new native action coverage;
- runtime execution;
- registry login or inferred credentials;
- shell or Docker CLI projection;
- cross-job artifact transfer;
- Tekton PipelineRun generation;
- relabeling the existing mixed deployment scenario.

## CLI contract

`reference-snapshot` accepts `--targets` with a comma-separated set of target ids. The selected scope is passed to the existing generator and is visible in `snapshot-index.json` through the target evidence list.

Omitting `--targets` preserves the existing all-built-in-target behavior. Empty target sets and unknown target ids fail closed.

## Honesty conditions

The reference is executable only when:

- compatibility contains no errors for the selected target;
- execution readiness allows generation;
- every action materializes as `NATIVE`;
- every native action has a resolved provider-owned renderer payload;
- render policy returns `EXECUTABLE`;
- the generated artifact contains real target syntax and no review wrapper;
- checkout is rendered before image build;
- no shell step appears;
- the committed bundle equals canonical generation file for file.

## Existing mixed scenario

`build-test-deploy` remains `MIXED` and non-executable. Its standard test, approval, deploy, verify, rollback and notification work does not borrow readiness from the new checkout/build proof.

## Version boundary

This is bounded roadmap work item `0.9.6.7`.

- Published implementation package remains `0.9.5`.
- Next package line remains `0.9.6`.
- Public Flow standard remains `0.8.0`.
- Intent, AST and ExecutionPlan remain `2.0`.
- Target Registry and Target Manifest remain `3.0`.

## Consequences

Flow now has its first committed executable multi-step reference without introducing a runtime or weakening mixed-scenario honesty. Future executable references must prove both action coverage and target-specific continuity between actions.
