# AI Intent Normalization Layer v1.0

Status: Draft implemented in Flow `0.3.0-rc1.8.3`.

## Purpose

The AI Intent Normalization Layer turns human automation requests into a validated, platform-neutral `IntentDocument`.

It is deliberately **not** a Jenkinsfile, GitHub Actions, Tekton, or Argo Workflow generator. The layer exists before AST lowering and before target rendering.

```text
Human / AI request
  -> AI Intent Normalization
  -> Normalized Intent
  -> Intent capability validation
  -> Flow AST
  -> Execution Plan
  -> Target Manifest
  -> Jenkins / GitHub Actions / Tekton / ...
```

## Core rule

AI output must never bypass validation.

A provider may propose an intent, but Flow must validate it through:

1. schema / model validation,
2. intent capability validation,
3. risk and clarification checks,
4. AST validation,
5. execution plan validation,
6. target compatibility validation.

## Components

### Provider contract

```kotlin
interface AiIntentProvider {
    fun normalize(request: AiIntentRequest): AiIntentResponse
}
```

The core package ships a deterministic `ScenarioPackIntentNormalizer`. Real LLM adapters should be implemented outside core and must return the same `AiIntentResponse` structure.

### Normalization report

Every normalization result includes:

- classification,
- confidence score,
- extracted entities,
- assumptions,
- open questions,
- risks,
- guardrails.

Required open questions block strict lowering.

### Assumptions

The normalizer may infer missing values, but every inference must be reported as an assumption. Silent defaults are forbidden at this layer.

### Open questions

The normalizer separates ambiguity into:

- `REQUIRED`,
- `RECOMMENDED`,
- `OPTIONAL`.

Required open questions prevent strict usage.

## CLI

```bash
./gradlew run --args='normalize "Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure." --target jenkins --strict --render --out generated/ai-deploy'
```

For file input:

```bash
./gradlew run --args='normalize --file examples/ai-normalization/deploy-request.txt --target github-actions --render'
```

## Why scenario packs first?

The scenario-pack normalizer is intentionally deterministic and limited. It allows the standard pipeline to be tested without depending on any specific AI provider or network access. Actual AI providers can be added later as adapters.

## Work in progress

- richer entity extraction,
- reverse engineering existing Jenkins / GitHub Actions / Tekton definitions,
- provider adapters,
- stricter risk policy engine,
- golden normalization vectors.
