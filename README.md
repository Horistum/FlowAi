# Horistum

<a id="flow-ai"></a>

Horistum is an AI-first standardization layer for IT and DevOps automation intent.

It is designed to turn human or AI-written automation requests into a validated, portable automation model that can be checked against target capabilities before any target-specific output is generated.

Horistum is not a runtime executor, SDK-first platform, plugin lifecycle framework, Jenkins DSL, Kubernetes DSL, GitHub Actions DSL, or general-purpose programming language. Those boundaries are deliberate. The project exists to define a stable automation standard that can be projected to multiple DevOps platforms without making the standard depend on any one of them.

## Product name and technical compatibility

Horistum is the current product name; the project was previously presented as **Flow AI**. This is the same project, not a fork, a new standard, or a runtime product.

Existing technical names are retained deliberately: the repository remains `milank78git/FlowAi`, Kotlin packages remain `org.flowlang`, and the Flow language, Flow AST, `flow-*` modules, CLI commands and artifact formats keep their current identities. The commands below continue to use the existing toolchain. No `horistum` executable or new repository URL is introduced by this branding change.

Use **Horistum** for product-facing prose and keep the exact technical identifier when discussing code, formats or tooling. There is no planned requirement to rename every function, type or package. See [Product identity and retained technical names](docs/PRODUCT_IDENTITY.md) and [Responsibility-based code names](docs/CODE_NAMING.md).

## Core idea

Modern automation is fragmented across CI/CD systems, Kubernetes tools, workflow engines, scripts, platform-specific YAML files and internal conventions. AI can help users express automation intent, but raw AI output is unsafe unless it is normalized, validated and checked against a deterministic standard.

Horistum separates the automation problem into stable layers:

```text
Human or AI intent
  -> Standard Intent Model
  -> Capability and safety validation
  -> Flow AST
  -> Flow validation
  -> Execution Plan
  -> Target compatibility analysis
  -> Target manifest
  -> Optional vendor renderer
```

The important point is that the AI does not directly generate arbitrary Jenkinsfiles, GitHub Actions workflows, Tekton pipelines or shell scripts. AI proposes intent. Horistum validates and plans. Target generators only project an already validated execution plan.

<a id="what-flow-is"></a>

## What Horistum is

Horistum is:

- an AI-first automation intent standard,
- a portable model for DevOps and IT automation,
- a validation and planning layer,
- a conformance-tested public standard surface,
- a compatibility negotiation model for target platforms,
- a safety boundary between natural language and executable automation,
- a way to describe automation intent without hardcoding one vendor workflow language.

<a id="what-flow-is-not"></a>

## What Horistum is not

Horistum is not:

- a runtime executor,
- an SDK-first framework,
- a plugin lifecycle platform,
- a target-specific public DSL,
- a replacement for Jenkins, GitHub Actions, Tekton, Argo Workflows or Kubernetes,
- a general-purpose programming language,
- a system that trusts AI-generated code directly.

If a future change moves the project toward one of those directions, that change is architectural drift and should be rejected or redesigned.

## Main architecture

### 1. Intent

Intent is the user's automation goal expressed as structured data or normalized from natural language.

Examples:

- deploy an application,
- run a database migration with backup and rollback,
- renew a certificate,
- rotate a secret,
- run Kubernetes maintenance,
- perform cleanup with safety constraints,
- generate a runbook or incident workflow.

Intent is not target syntax. It should not contain Jenkins-specific, GitHub-specific or Tekton-specific implementation details unless they are explicitly modeled as target constraints.

### 2. Scenario packs

Scenario packs are deterministic normalization guides. They help classify common automation requests, extract entities, ask clarification questions and produce a standard intent proposal.

Scenario packs must remain quality-controlled. Each pack should have metadata, examples, declared capabilities and negative coverage for risky capabilities.

### 3. Capability validation

Capability validation checks whether the intent is safe and complete enough to lower.

Examples of blocking cases:

- production deployment without explicit approval,
- database migration without backup or rollback strategy,
- cleanup without retention or scope,
- certificate renewal without certificate identity,
- Kubernetes maintenance without maintenance window or dry-run expectations.

### 4. Flow AST

The Flow AST is a platform-neutral syntax tree. It represents the automation structure without owning execution.

The AST is not the runtime. It is the standard's structured representation of the automation definition.

### 5. Execution Plan

The Execution Plan is the public contract between validated Flow and target projection.

It preserves tasks, approvals, dependencies, conditions, loops, safety metadata, inputs, outputs and required capabilities. Target generators should consume the Execution Plan instead of reinterpreting the original intent.

### 6. Target compatibility

Target compatibility reports explain what each target can or cannot support.

A target may be:

- ready,
- degraded,
- blocked.

For example, Jenkins may support a workflow fully, GitHub Actions may need workarounds for manual approval, and Tekton may be blocked for a feature it cannot safely express.

### 7. Target manifest

The target manifest is a platform-neutral projection artifact. It is the auditable bridge between the Execution Plan and optional vendor output.

Renderers may emit Jenkinsfile, GitHub Actions YAML or Tekton YAML, but renderers must not re-plan intent or invent semantics.

## Repository structure

```text
src/main/kotlin/org/flowlang/
  ai/                 AI intent normalization support
  architecture/       architecture governance checks
  artifacts/          public standard artifact reports
  capabilities/       target capability and readiness analysis
  conformance/        conformance runner and vector support
  generators/         target manifest and renderer code
  intent/             standard intent model and validation
  modules/            module contract model
  parser/             Flow parser
  planner/            execution plan creation
  scenarios/          scenario pack registry
  standard/           public standard model and quality checks
  validator/          Flow AST validator

examples/             example Flow and intent inputs
modules/              module descriptors
targets/              target capability descriptors
conformance/          conformance vectors and snapshots
schemas/              public JSON schemas
standard/             architecture and governance data
docs/                 documentation and ADRs
.flow-agent/          roadmap, quality gates and agent governance
```

## Common commands

Run all tests:

```bash
./gradlew clean test
```

Run conformance:

```bash
./gradlew run --args="conformance"
```

Export conformance artifacts:

```bash
./gradlew run --args="conformance --out generated/conformance"
```

CLI argument syntax and strict validation rules are documented in [Typed CLI argument parsing](docs/migrations/typed-cli-arguments.md).
See [packaged CLI contracts](docs/migrations/packaged-cli-contracts.md) for relocatable distributions, resource provenance and explicit contract overrides.

Render a Jenkins artifact bundle from an intent file:

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target jenkins --strict --render --out generated/jenkins"
```

Render a GitHub Actions artifact bundle:

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target github-actions --render --out generated/github"
```

Render a partial Tekton artifact bundle:

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target tekton --render --out generated/tekton"
```

Normalize natural language into standard intent:

```bash
./gradlew run --args='normalize "Deploy billing-api to Kubernetes. Require approval in production and verify health after deploy." --target jenkins --strict --render --out generated/ai-deploy'
```

List scenario packs:

```bash
./gradlew run --args="scenarios"
./gradlew run --args="scenarios --markdown"
./gradlew run --args="scenario deployment --examples"
```

Inspect standard catalogs:

```bash
./gradlew run --args="catalog"
./gradlew run --args="diagnostics"
./gradlew run --args="release-profile"
./gradlew run --args="targets"
./gradlew run --args="modules"
```

Export the public standard bundle:

```bash
./gradlew run --args="standard-export --out dist/flow-standard"
```

Verify an exported standard bundle:

```bash
./gradlew run --args="standard-verify --bundle dist/flow-standard"
```

## Public artifacts

A full intent or normalization export can include:

```text
standard-version.txt
standard-diagnostic-catalog.json
normalized-intent.json
intent-design-report.json
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
target-manifest.json
Jenkinsfile | github-actions.yml | tekton-pipeline.yaml
artifact-integrity-report.json
standard-contract-index.json
standard-release-profile.json
artifact-evidence-report.json
standard-compliance-report.json
standard-freeze-report.json
compatibility-policy.json
reference-corpus-index.json
negative-conformance-corpus.json
target-conformance-profile.json
public-standard-surface.json
compatibility-migration-policy.json
reference-intent-corpus.json
target-semantics-matrix.json
standard-export-bundle.json
conformance-levels.json
standard-export-manifest.json
conformance-vector-index.json
standard-index.json
conformance-suite.json
flow-standard-draft.json
flow-artifact-bundle.json
```

## Quality rules

Every meaningful change should preserve these rules:

- Do not add runtime execution to the core standard.
- Do not create an SDK-first architecture.
- Do not add a plugin lifecycle framework.
- Do not expose a target-specific public DSL.
- Do not hide unsupported target behavior behind silent fallback.
- Do not bend code only to make tests pass.
- Do not move target rendering semantics into module descriptors.
- Keep source text, tests, examples, documentation and generated artifacts in English.
- Add tests for behavior changes.
- Add conformance vectors for public standard gates.
- Keep the project aligned with the roadmap in `.flow-agent/roadmap.yaml`.

## Development workflow

Recommended workflow:

1. Read `.flow-agent/release-state.yaml` and `.flow-agent/roadmap.yaml`.
2. Select the next roadmap item.
3. Create a small branch with a clear scope.
4. Change the smallest safe surface.
5. Add tests before claiming behavior is fixed.
6. Run `./gradlew clean test`.
7. Run `./gradlew run --args="conformance"`.
8. Update `CHANGELOG.md`.
9. Open a pull request.
10. Merge only after CI passes.

## License

Horistum is licensed under the Apache License, Version 2.0. See `LICENSE`.
