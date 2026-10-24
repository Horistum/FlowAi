# Horistum

Start with the [current architecture](docs/ARCHITECTURE.md) and the
[active-roadmap route](.flow-agent/README.md#active-roadmap-route).

Action-local result handler rules currently fail compilation explicitly; see the
[action result handler admission and migration contract](docs/ACTION_RESULT_HANDLER_CONTAINMENT.md).

Global `vars` declarations also fail compilation until initialization and value
lifetime have a canonical contract; see the
[global initializer admission boundary](docs/GLOBAL_VARIABLE_INITIALIZER_CONTAINMENT.md).

Inputs and approval parameters have explicit value admission contracts; see the
[input and approval value integrity rules](docs/INPUT_APPROVAL_VALUE_INTEGRITY.md).

Availability analysis has deterministic compiler resource budgets; see
[availability analysis limits and rejection behavior](docs/AVAILABILITY_ANALYSIS_LIMITS.md).

<a id="flow-ai"></a>

Horistum is an AI-first standardization layer for IT and DevOps automation intent.

It is designed to turn human or AI-written automation requests into a validated, portable automation model that can be checked against target capabilities before any target-specific output is generated.

Horistum is not a runtime executor, SDK-first platform, plugin lifecycle framework, Jenkins DSL, Kubernetes DSL, GitHub Actions DSL, or general-purpose programming language. Those boundaries are deliberate. The project exists to define a stable automation standard that can be projected to multiple DevOps platforms without making the standard depend on any one of them.

## Product name and technical compatibility

Horistum is the current product name; the project was previously presented as **Flow AI**. This is the same project, not a fork, a new standard, or a runtime product.

Existing technical names are retained deliberately: the repository retains the technical name `FlowAi`, Kotlin packages remain `org.flowlang`, and the Flow language, Flow AST, `flow-*` modules, CLI commands and artifact formats keep their current identities. The commands below continue to use the existing toolchain. No `horistum` executable or new repository URL is introduced by this branding change.

Use **Horistum** for product-facing prose and keep the exact technical identifier when discussing code, formats or tooling. There is no planned requirement to rename every function, type or package. See [Product identity and retained technical names](docs/PRODUCT_IDENTITY.md) and [Responsibility-based code names](docs/CODE_NAMING.md).

## Core idea

Modern automation is fragmented across CI/CD systems, Kubernetes tools, workflow engines, scripts, platform-specific YAML files and internal conventions. AI can help users express automation intent, but raw AI output is unsafe unless it is normalized, validated and checked against a deterministic standard.

Flow Source, Intent YAML and reviewed AI proposals enter one deterministic compiler. It validates and plans accepted meaning, constructs an authorized canonical execution graph, and exposes derived plan views. Explicitly selected adapters then pass materialization gates before producing target artifacts. AI proposals cannot bypass that boundary or define target syntax directly.

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

The [architecture guide](docs/ARCHITECTURE.md) is the current implementation narrative and module map.

- Frontends own source parsing and provenance. The shared compiler owns Intent/Flow validation, availability analysis, planning and canonical authorization.
- The typed canonical execution graph owns represented meaning. Execution plans and public artifact views are derived from it; graph integrity does not replace source-preservation checks.
- Canonical capabilities, effects and controls remain separate from module implementation bindings and the selected orchestration target.
- Reference composition supplies Jenkins, GitHub Actions and Tekton providers. Materialization checks capability, control, continuity, topology and readiness for the particular plan before a provider renders.
- Registry declarations, provider scopes, executable readiness, authenticated runtime assessments and CI composition observations are distinct claims. No target is generally supported because an example renders.
- Scenario normalization remains heuristic. Reviewed AI proposals retain provider/request/response provenance and pass deterministic review; confidence cleanup and compatibility facade retirement remain AR-07-owned.

Unsupported authored meaning is rejected explicitly. The admission contracts linked above and the [data-operation payload boundary](docs/DATA_OPERATION_PAYLOAD_CONTAINMENT.md) describe current limits; they do not implement the rejected features.

## Repository structure

Fourteen Gradle modules compile the implementation independently; see the [module ownership table](docs/ARCHITECTURE.md#actual-build-boundaries). The directories below are retained source and resource locations, not module boundaries. Sorted source manifests under `gradle/` determine each file's owner, and the root build aggregates verification and distributions.

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

The `targets` command publishes registry declarations, provider structural mapping
scopes and repository reference maturity. It does not assess native runtime
certification or authorize a plan. See the [target maturity report v2 migration](docs/migrations/target-maturity-report-v2.md)
for the report contract and the separate authenticated coverage reports.
The [composition coverage report](docs/validation/composition-coverage.md) reconciles
signed scenarios with independently replayed CI composition observations and their remaining gaps.

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
- Select work through the [active-roadmap route](.flow-agent/README.md#active-roadmap-route) and preserve its existing authority boundaries.

## Development workflow

Recommended workflow:

1. Read the agent contract, constitution and release state; follow the [active-roadmap route](.flow-agent/README.md#active-roadmap-route).
2. Select the authorized item and create its bounded work package before implementation.
3. Create a small branch with a clear scope.
4. Change the smallest safe surface.
5. Add tests before claiming behavior is fixed.
6. Run `./gradlew clean test`.
7. Run `./gradlew run --args="conformance"`.
8. Record the bounded report and validation evidence; update release state after validation. Update release notes when release behavior changes.
9. Open a pull request.
10. Merge only after CI passes.

## License

Horistum is licensed under the Apache License, Version 2.0. See `LICENSE`.
