# v0.9.5.1 Shell Usage Inventory and Prohibition

## Purpose

v0.9.5.1 records the first correction inventory after the architecture recenter.

The goal is not to remove every legacy command-oriented path in one change. The goal is to identify every known place where Flow currently depends on shell, raw command strings, echo placeholders or hardcoded CLI tools, and to classify those paths as legacy defects scheduled for removal.

Flow Core must move toward notes-driven semantic actions and honest materialization negotiation.

## Rule

Shell must not be treated as the universal action model.

A raw command string must not be treated as the default representation of executable automation.

Semantic-only echo output must not be treated as successful materialization.

## Inventory summary

### 1. TargetManifest command field

Current location:

```text
src/main/kotlin/org/flowlang/generators/manifest/TargetManifest.kt
TargetStep.run
TaskNode.toTargetStep(...)
```

Problem:

`TargetStep.run` stores a raw command string. `TaskNode.toTargetStep` always derives that command by calling `runCommandFor`. This makes command projection the central TargetManifest representation instead of a temporary legacy adapter detail.

Required correction:

Replace `TargetStep.run` as semantic truth with structured semantic action and materialization metadata.

### 2. Portable shell naming

Current location:

```text
PlanNode.toTargetSteps(targetName = "portable-shell")
TaskNode.toTargetStep(targetName = "portable-shell")
runCommandFor(..., targetName = "portable-shell")
```

Problem:

The implementation calls the fallback portable even though the projection depends on shell syntax, environment variables and concrete CLI tools.

Required correction:

Stop calling this target-neutral or portable. Reclassify it as a legacy command projection defect until it is removed.

### 3. Hardcoded CLI mappings

Current location:

```text
runCommandFor(...)
PORTABLE_ACTIONS
```

Known tool assumptions:

- `git clone`
- `docker build`
- `docker push`
- `helm template`
- `helm upgrade`
- `kubectl apply`
- `kubectl set image`
- `kubectl rollout status`
- `kubectl get`
- `argocd app sync`
- `argocd app wait`
- `argocd app get`
- `curl`
- `psql`
- `printf`
- `mail`
- `echo`

Problem:

These are implementation details, not universal Flow semantics. They require a specific execution substrate, installed tools, credentials, network access and operating system behavior that Flow does not currently model.

Required correction:

Move toward Runtime Notes and Projection Notes that declare requirements and materialization behavior explicitly.

### 4. Standard capability echo placeholders

Current location:

```text
standardExecuteCommand(...)
standard.execute
standard.rollback
```

Affected operations include:

- schedule
- backup
- restore
- data-sync
- data-transform
- validate
- secret-rotate
- provision
- cleanup
- runbook
- incident
- policy-check
- custom standard capability

Problem:

These operations are rendered as echo text. That is useful as an audit note, but it is not materialization of the intended automation.

Required correction:

Classify these outputs as `SEMANTIC_ONLY` until a notes-driven projection can materialize them.

### 5. Renderer script surfaces

Current location:

```text
src/main/kotlin/org/flowlang/generators/manifest/TargetManifestRenderers.kt
```

Known cases:

- Jenkins renderer emits `sh(script: ...)` for generated command lines.
- GitHub Actions renderer emits `run: |` blocks and echo fallback text.
- Tekton renderer emits `script: |`, `#!/bin/sh`, `set -eu` and command lines inside an Alpine image.
- Renderer helper `toShellLines()` converts non-action nodes and missing runs into echo lines.

Problem:

Renderers currently preserve command-oriented projection instead of materializing structured semantic actions through target/projection notes.

Required correction:

Renderers must consume notes-driven materialization outputs instead of inventing shell/script surfaces.

### 6. Intent lowering into command-oriented behavior

Current location:

```text
src/main/kotlin/org/flowlang/intent/IntentToAstPlanner.kt
```

Known cases:

- `TEST` defaults to `mvn test`.
- `BUILD` defaults to `mvn package`.
- `PACKAGE` defaults to `mvn package`.
- `RUN_COMMAND` lowers directly to `shell.run`.
- `BUILD_IMAGE` lowers to `docker.build`.
- `PUSH_IMAGE` lowers to `docker.push`.
- `DEPLOY` lowers to `argocd.sync` or `kubernetes.deploy`.
- `VERIFY` lowers to `shell.run` when command is provided, otherwise to `kubernetes.get`.
- many non-CI/CD standard capabilities lower to `standard.execute`.

Problem:

Universal capabilities are lowered into concrete tools before notes-driven capability, runtime and materialization negotiation exists.

Required correction:

Lower intent to semantic actions first. Tool-specific projection must happen later through notes.

### 7. Scenario pack deployment conventions

Current location:

```text
src/main/kotlin/org/flowlang/scenarios/ScenarioPacks.kt
```

Known cases:

- deployment normalization creates Kubernetes or Argo CD systems
- deployment normalization creates a Docker registry system
- test assumptions include `mvn test`
- verify step defaults to Kubernetes pods

Problem:

Scenario normalization pushes CI/CD and Kubernetes conventions into intent before the notes-driven model can decide.

Required correction:

Scenario packs should produce target-neutral semantic intent and unresolved questions when the implementation domain is not declared.

## Legacy defect classification

The following paths are now classified as legacy defects:

- command string stored in TargetManifest as execution truth
- `portable-shell` naming
- `runCommandFor` as target-neutral command generator
- `PORTABLE_ACTIONS`
- concrete CLI mappings in core projection
- echo-based standard capability materialization
- renderer script blocks as default projection
- Maven/Docker/Kubernetes defaults in intent lowering
- scenario pack CI/CD defaults that pretend to be universal

## Prohibition

New code must not add new command-oriented mappings to Flow Core.

New code must not add new echo placeholders that are reported as successful materialization.

New code must not add new hardcoded concrete tools as universal default lowering.

Any future command-oriented compatibility path must be explicitly marked as legacy, degraded or blocked until it is replaced by notes-driven materialization.

## Next step

v0.9.5.2 must inventory the broader CI/CD bias separately from command projection. That includes hardcoded target names, registry-only target claims, tool defaults and conformance coverage gaps.
