# Build/Test/Deploy Review-Only Reference Snapshot

This directory records the public semantic path for the `build-test-deploy` reference scenario and the current target projection evidence.

It is **not** an end-to-end execution proof. The current Jenkins, GitHub Actions and Tekton outputs are review-only artifacts because required materialization and renderer payload evidence is incomplete.

The committed files represent:

```text
Intent YAML
  -> normalized intent snapshot (SEMANTIC_ONLY, non-executable)
  -> Flow AST snapshot (SEMANTIC_ONLY, non-executable)
  -> canonical Execution Plan snapshot (SEMANTIC_ONLY, non-executable)
  -> target projection evidence
       -> jenkins.review.yaml (REVIEW_ONLY, non-executable)
       -> github-actions.review.yaml (REVIEW_ONLY, non-executable)
       -> tekton.review.yaml (REVIEW_ONLY, non-executable)
```

`snapshot-index.json` is the authoritative claim. It records capability compatibility, effective compatibility, materialization readiness, projection readiness, render mode and executable state for every target.

Review-only files deliberately do not use executable-looking names such as `Jenkinsfile`, `github-actions.yml` or `tekton-pipeline.yaml`. Those names are reserved for artifacts backed by complete materialization and concrete renderer payload evidence.

Snapshot updates must be generated from the real parser, intent planner, Flow planner, compatibility analyzer, manifest generators and render policy. A snapshot must never be edited merely to make conformance green.
