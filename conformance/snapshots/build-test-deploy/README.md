# Build/Test/Deploy Mixed Reference Snapshot

This directory records the real semantic and target-projection evidence for the `build-test-deploy` reference scenario.

It is **not** an end-to-end execution proof. The built-in distribution has reviewed native `git.checkout` and `docker.build` evidence for Jenkins, GitHub Actions and Tekton, but this mixed scenario still contains standard execution, deployment, notification, approval and rollback requirements that remain notes-projected, adapter-required or unsupported according to each target. The completed **0.9.6.7 First Executable Reference Scenario** is committed separately under `conformance/snapshots/checkout-build-image` and does not change this scenario's MIXED state.

## Version boundary

| Axis | Current value | Meaning |
|---|---:|---|
| Implementation package | `0.9.5` | The distributable Kotlin/Gradle release line. |
| Public Flow standard | `0.8.0` | The semantic language and exported standard contract. |
| Serialized artifact contracts | `2.0` | Intent, AST, ExecutionPlan, TargetManifest and TargetRegistry shapes. |
| Historical correction scope | `0.9.5.7.9` | The bounded repair item that introduced snapshot honesty; it is not a release version. |

`snapshot-index.json` carries these axes explicitly so consumers do not have to infer them from file names.

## Evidence path

```text
Intent YAML
  -> normalized-intent.json       (SEMANTIC_ONLY)
  -> flow-ast.json                (SEMANTIC_ONLY)
  -> execution-plan.json          (SEMANTIC_ONLY)
  -> target evidence
       -> jenkins.review.yaml      (REVIEW_ONLY, manifest present, non-executable)
       -> github-actions.blocked.json (FAIL_FAST, no manifest, no target YAML)
       -> tekton.blocked.json      (FAIL_FAST, no manifest, no target YAML)
```

Jenkins preserves the pipeline as a review artifact because required materialization and renderer payload evidence is incomplete. GitHub Actions remains blocked without the required workspace-continuity evidence. Tekton fails before manifest generation because the current scenario requires unsupported approval and rollback capabilities.

Review-only files deliberately avoid executable-looking names such as `Jenkinsfile` or `github-actions.yml`. A blocked target uses JSON diagnostic evidence rather than a `.yaml` file, because no target syntax was emitted.

Snapshot updates must be generated with the `reference-snapshot` command, which uses the real intent loader, validators, planners, compatibility analyzer, canonical manifest pipeline and render policy. Do not edit generated output merely to satisfy conformance expectations.

## Whole-intent approval coverage

The authored YAML declares an unconditional intent-wide approval policy. The approval is therefore the predecessor of checkout and, transitively, of test, image build, deployment and verification. Moving the gate back to the deployment boundary while retaining that policy is a negative case: it must be rejected, because the earlier operations would be uncovered. The compiler does not insert this ordering on the user's behalf.

This reference is not a deployment-only approval policy and is not evidence that conditional approvals have been executed. Conditional policies retain their separate pending-evidence contract.
