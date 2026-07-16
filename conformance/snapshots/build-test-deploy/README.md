# Build/Test/Deploy Mixed Reference Snapshot

This directory records the real semantic and target-projection evidence for the `build-test-deploy` reference scenario.

It is **not** an end-to-end execution proof. The built-in distribution now has reviewed native `git.checkout` and `docker.build` evidence for Jenkins, GitHub Actions and Tekton, but this mixed scenario still contains standard execution, deployment, notification, approval and rollback requirements that remain notes-projected, adapter-required or unsupported according to each target. Promoting a complete executable scenario belongs to roadmap item **0.9.6.7 First Executable Reference Scenario** and must be derived from the real pipeline rather than inferred from isolated native actions.

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
       -> github-actions.review.yaml (REVIEW_ONLY, manifest present, non-executable)
       -> tekton.blocked.json      (FAIL_FAST, no manifest, no target YAML)
```

Jenkins and GitHub Actions preserve the realistic pipeline as review artifacts because required materialization and renderer payload evidence is incomplete. Tekton fails before manifest generation because the current scenario requires unsupported approval and rollback capabilities.

Review-only files deliberately avoid executable-looking names such as `Jenkinsfile` or `github-actions.yml`. A blocked target uses JSON diagnostic evidence rather than a `.yaml` file, because no target syntax was emitted.

Snapshot updates must be generated with the `reference-snapshot` command, which uses the real intent loader, validators, planners, compatibility analyzer, canonical manifest pipeline and render policy. Editing snapshots merely to satisfy conformance is forbidden, as it should be in any project that has not entirely surrendered to decorative testing.
