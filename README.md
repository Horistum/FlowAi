# FlowAi

FlowAi is an AI-first standardization layer for IT and DevOps automation.

It is designed to translate human or AI-described automation intent into a validated, platform-neutral model that can be checked, planned and projected to different automation targets such as Jenkins, GitHub Actions, Tekton and other workflow systems.

FlowAi is not a runtime executor, not an SDK-first framework, not a plugin lifecycle platform, not a Jenkins-specific DSL and not a general-purpose programming language. Its purpose is to define a stable automation standard: intent in, validated model out, target projection only after safety and compatibility checks pass.

## Why FlowAi exists

Modern automation is fragmented. Teams describe the same operational idea many times in many incompatible forms: Jenkins pipelines, GitHub Actions workflows, Tekton pipelines, Argo workflows, shell scripts, Kubernetes jobs, runbooks and ticket-driven procedures. Each target has its own syntax, lifecycle, secret handling, approval model, error behavior and portability limits.

AI can help translate intent into automation, but raw AI output must not be trusted as executable infrastructure code. FlowAi adds a deterministic standard boundary between user intent and target-specific automation output.

The core idea is simple:

```text
Human or AI intent
  -> Standard Intent Model
  -> Capability and safety validation
  -> Flow AST
  -> Execution Plan
  -> Target compatibility analysis
  -> Target manifest
  -> Optional target renderer output
```

The standard decides whether an automation proposal is valid. A model or provider may propose intent, but it does not decide whether that intent is safe, portable or complete.

## Core principles

FlowAi follows these principles:

- Intent first: describe what automation should achieve before choosing a target platform.
- Platform neutrality: the core model must not become Jenkins, GitHub Actions, Tekton or Kubernetes syntax in disguise.
- Deterministic validation: AI output must pass standard validators before it can be lowered.
- Explicit safety: risky operations require explicit safety requirements such as approval, backup, dry-run, rollback or maintenance windows.
- Capability negotiation: targets are evaluated against required capabilities before manifests are generated.
- No silent semantic fallback: if a target cannot express a required behavior, the system must report it instead of pretending everything is fine.
- Standard artifacts over hidden behavior: important decisions are exposed as JSON reports and conformance checks.
- English-only repository content: source code, tests, examples, documentation and generated artifacts are written in English.

## What FlowAi is not

FlowAi deliberately avoids several directions:

```text
not a runtime executor
not an SDK-first architecture
not a plugin framework
not a target-specific public DSL
not a Jenkins language
not a general-purpose programming language
not a replacement for target platforms
```

Target platforms remain responsible for executing their own workflows. FlowAi defines the standard model, checks, compatibility reports and target manifests that can be rendered to those platforms.

## Main architecture

FlowAi is organized around a layered boundary:

```text
Intent input
  -> Intent normalization
  -> Intent capability validation
  -> Flow AST
  -> Flow validation
  -> Execution Plan
  -> Compatibility reports
  -> Target manifest
  -> Target renderer
```

### Intent model

The intent model is the high-level automation request. It contains workflows, steps, capabilities, parameters, safety requirements and expected behavior. Intent documents are easier for humans and AI systems to produce than target-specific YAML.

### Flow AST

The AST is the standard structural representation of Flow. It preserves actions, conditions, approvals, loops, error handling and data operations without committing to one execution engine.

### Execution Plan

The execution plan is the public contract between Flow validation and target projection. It is target-neutral and contains nodes, dependencies, required capabilities, assumptions and outputs.

### Target capability model

Target capability files describe what a target can represent. A target may be ready, degraded or blocked depending on its support for approvals, parallelism, conditions, secrets, artifacts and other capabilities.

### Target manifest

A target manifest is the auditable intermediate artifact between the execution plan and a vendor-specific rendered file. Renderers should render manifests, not reinterpret intent.

## Safety model

FlowAi treats destructive or high-risk automation as incomplete unless the required safety obligations are explicit.

Examples of safety-sensitive operations include:

- production deployment,
- database migration,
- cleanup or deletion,
- Kubernetes maintenance,
- secret rotation,
- certificate renewal,
- rollback or recovery workflows.

The standard validates required safety policies through deterministic rules. AI-generated text cannot bypass these rules by simply omitting risks from its own report. Delightful, because apparently infrastructure should not rely on vibes.

## Scenario packs

Scenario packs connect natural-language automation requests to structured intent patterns. They are deterministic classification and normalization helpers, not provider-specific AI logic.

A scenario pack may define:

- supported trigger phrases,
- required and optional entities,
- expected capabilities,
- risks,
- example requests,
- normalization behavior.

Scenario packs are validated by quality gates so they remain useful and do not become decorative metadata.

Useful commands:

```bash
./gradlew run --args="scenarios"
./gradlew run --args="scenarios --markdown"
./gradlew run --args="scenario deployment --examples"
```

## Conformance

Conformance is the primary protection against architectural drift. It verifies that the public standard behavior still works through actual validation, planning, target compatibility and rendering paths.

Run conformance:

```bash
./gradlew run --args="conformance"
```

Export conformance artifacts:

```bash
./gradlew run --args="conformance --out generated/conformance"
```

Conformance checks are intentionally strict. They should fail when behavior drifts, instead of becoming a decorative green badge that makes everyone feel productive while the standard quietly rots.

## Usage

### Run tests

```bash
./gradlew clean test
```

Offline builds are supported when the Gradle wrapper and dependency cache are available:

```bash
./gradlew --offline --no-daemon clean test
```

### Run conformance

```bash
./gradlew run --args="conformance"
```

### Generate a Jenkins artifact bundle

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target jenkins --strict --render --out generated/jenkins"
```

### Generate a GitHub Actions artifact bundle

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target github-actions --render --out generated/github"
```

### Generate a partial Tekton artifact bundle

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target tekton --render --out generated/tekton"
```

### Normalize natural-language automation text

```bash
./gradlew run --args='normalize "Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure." --target jenkins --strict --render --out generated/ai-deploy'
```

### Inspect catalogs and contracts

```bash
./gradlew run --args="catalog"
./gradlew run --args="diagnostics"
./gradlew run --args="targets"
./gradlew run --args="modules"
./gradlew run --args="standard-draft --out generated/standard-draft"
./gradlew run --args="standard-export --out generated/standard-export"
```

## Public artifacts

FlowAi produces machine-readable artifacts such as:

```text
standard-version.txt
normalized-intent.json
intent-decision-report.json
intent-capability-validation-report.json
flow-ast.json
validation-report.json
execution-plan.json
canonical-execution-plan.json
compatibility-report.json
capability-negotiation-report.json
execution-readiness-report.json
target-selection-report.json
target-decision-trace-report.json
target-adapter-contract.json
adapter-diagnostics.json
diagnostic-coverage-report.json
artifact-integrity-report.json
standard-contract-index.json
standard-release-profile.json
artifact-evidence-report.json
standard-compliance-report.json
standard-freeze-report.json
compatibility-policy.json
reference-intent-corpus.json
target-semantics-matrix.json
standard-export-bundle.json
conformance-levels.json
standard-export-manifest.json
conformance-vector-index.json
conformance-manifest.json
target-manifest.json
```

These artifacts make the standard auditable. They also make drift harder to hide, which is inconvenient for chaos but useful for software.

## Repository structure

```text
src/main/kotlin/org/flowlang
  ai/                intent normalization boundary
  artifacts/         public standard reports and artifact contracts
  capabilities/      target capability and readiness analysis
  conformance/       executable conformance runner and vector index
  generators/        target manifest and renderer projection
  intent/            standard intent model and validators
  modules/           capability module contracts
  parser/            Flow parser and expression parser
  planner/           execution plan model and lowering
  scenarios/         scenario pack registry
  standard/          standard model, diagnostics and governance checks
  validator/         Flow AST validator

conformance/         public conformance vectors
schemas/             public JSON schemas
standard/            architecture and governance standard data
targets/             target capability registry
examples/            example Flow and intent documents
docs/                architecture and public standard documentation
.flow-agent/         roadmap, guardrails and development-agent context
```

## Development rules

Every meaningful change should follow this path:

1. Identify the roadmap goal and layer.
2. Keep the change small and reviewable.
3. Add or update tests for public behavior.
4. Add or update conformance vectors when behavior becomes part of the public standard.
5. Keep documentation in English.
6. Avoid adding runtime, SDK, plugin or target-specific language behavior unless the roadmap explicitly permits it.
7. Run tests and conformance.

The preferred proof is not a confident paragraph. It is a passing test and conformance run.

## License

FlowAi is licensed under Apache License 2.0. See `LICENSE` for the repository license marker.

## Status

FlowAi is still evolving toward a public standard line. The project currently prioritizes model correctness, conformance coverage, architectural boundaries and documentation quality over feature volume.

The next development direction is to continue strengthening public contract gates, target capability matrices and compatibility negotiation without turning the project into yet another workflow framework wearing a tiny fake mustache.
