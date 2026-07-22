# Checkout and Build Image Executable Reference

This directory is the first committed **executable** multi-step reference evidence in Flow Core.

The evidence is deliberately target-scoped to **Jenkins**. The real Intent → AST → ExecutionPlan → compatibility → materialization → Target Manifest → renderer pipeline proves that:

1. `git.checkout` is materialized through the reviewed Jenkins native Git step;
2. `docker.build` is materialized through the reviewed Jenkins Docker Pipeline object API;
3. checkout precedes image build in one generated Jenkins stage and therefore uses one target workspace;
4. every materialization leaf has a resolved structured renderer payload;
5. the generated artifact is executable target syntax rather than a review document or placeholder.

The image is not pushed, so the scenario does not invent registry credentials. It uses a public repository and the default Dockerfile in the checked-out workspace.

This is not a claim that every built-in target can execute the scenario. GitHub Actions currently lowers the two actions to separate jobs without explicit workspace-transfer evidence, so it is intentionally excluded. Tekton remains outside this first proof until its complete PipelineRun workspace contract is represented as committed reference evidence. Their isolated native action coverage remains valid, but isolated actions are not silently promoted into end-to-end readiness.

The existing `build-test-deploy` reference remains MIXED and non-executable because its test, approval, deploy, verify, rollback and notification work still lacks complete native projection evidence.

## Version boundary

- Implementation package: `0.9.5`
- Next package line: `0.9.6`
- Public Flow standard: `0.8.0`
- Intent, AST and ExecutionPlan: `2.0`
- Target Registry: `3.1`
- Target Manifest: `3.0`
