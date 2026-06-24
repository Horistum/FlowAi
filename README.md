# Flow Core Prototype v0.7.6

v0.7.6 is a semantic correctness hardening release on top of v0.7.5. It fixes concrete lowering, validation, interpolation, safety and snapshot-drift defects without changing Flow's main purpose. Flow remains an AI-first standardization layer for IT/DevOps automation intent, not a runtime executor, SDK API, plugin lifecycle or target-specific public DSL.

New in this version:

- `docs/PUBLIC_STANDARD_SURFACE.md`,
- `docs/COMPATIBILITY_POLICY.md`,
- `docs/REFERENCE_INTENT_CORPUS.md`,
- `docs/TARGET_SEMANTICS_MATRIX.md`,
- `docs/STANDARD_EXPORT_BUNDLE.md`,
- `public-standard-surface.json`,
- `compatibility-migration-policy.json`,
- `reference-intent-corpus.json`,
- `target-semantics-matrix.json`,
- `standard-export-bundle.json`,
- `conformance-levels.json`,
- `standard-export-manifest.json`,
- `standard-export` CLI command,
- `v0.4.5.standard-surface-freeze`,
- `v0.4.6.compatibility-migration-policy`,
- `v0.4.7.reference-intent-corpus`,
- `v0.4.8.target-semantics-matrix`,
- `v0.4.9.standard-export-bundle`,
- `v0.5.0.standard-export-manifest`,
- `v0.5.3.standard-bundle-verifier`,
- `v0.5.4.data-driven-conformance-index`,
- `v0.6.1.intent-corpus-expansion`,
- `v0.6.2.required-clarification-contract`,
- `v0.6.3.safety-policy-matrix`,
- `v0.6.4.target-semantics-negative-corpus`,
- `v0.6.5.execution-plan-semantic-invariants`,
- `v0.6.6.ai-input-trust-boundary`,
- `v0.6.7.standard-example-bundle`,
- `v0.6.8.compatibility-promise`,
- `v0.7.0.reference-corpus-execution-harness`.
- `v0.7.1.architecture-debt-cleanup-and-drift-enforcement`,
- `v0.7.3.standard-model-projection-coherence`,
- `v0.7.4.architecture-delta-analyzer`,
- `v0.7.5.purpose-coverage-ratio`.

The release does not add new Flow syntax, runtime execution, SDK APIs, plugin lifecycle or target renderer expansion.

Carried from v0.3.19:

- `StandardContractIndexAnalyzer`,
- `standard-contract-index.json`,
- `StandardReleaseProfile`,
- `standard-release-profile.json`,
- `ArtifactEvidenceAnalyzer`,
- `artifact-evidence-report.json`,
- `StandardComplianceAnalyzer`,
- `standard-compliance-report.json`.

Carried from v0.3.15:

- `ArtifactIntegrityAnalyzer`,
- `artifact-integrity-report.json`,
- `schemas/artifact-integrity-report.schema.json`,
- required-artifact presence checks,
- standard-version consistency checks,
- conformance and JUnit coverage for artifact integrity behavior.

Carried from v0.3.14:

- `DiagnosticCoverageAnalyzer`,
- `diagnostic-coverage-report.json`,
- `schemas/diagnostic-coverage-report.schema.json`,
- coverage checks for observed, unknown and unused diagnostic codes,
- CLI/export integration for intent and normalization lowering pipelines,
- conformance and JUnit coverage for diagnostic coverage behavior.

Carried from v0.3.13:

- `StandardDiagnosticCatalog`,
- `standard-diagnostic-catalog.json`,
- `schemas/standard-diagnostic-catalog.schema.json`,
- `diagnostics` CLI command,
- `diagnostics --out <dir>` export support,
- conformance and JUnit coverage for stable diagnostic code behavior.

Carried from v0.3.12:

- `TargetAdapterContractAnalyzer`,
- `target-adapter-contract.json`,
- `adapter-diagnostics.json`,
- `schemas/target-adapter-contract.schema.json`,
- `schemas/adapter-diagnostics.schema.json`,
- adapter invariants such as `ADAPTER_MUST_NOT_READ_INTENT` and `ADAPTER_MUST_RESPECT_READINESS`,
- conformance and JUnit coverage for adapter contract behavior.

Carried from v0.3.11:

- `ConformanceManifestBuilder`,
- `conformance-manifest.json`,
- `schemas/conformance-manifest.schema.json`,
- `./gradlew run --args="conformance --out generated/conformance"` export support,
- conformance and JUnit coverage for the conformance manifest.

Carried from v0.3.10:

- `FlowArtifactBundleAnalyzer`,
- `flow-artifact-bundle.json`,
- `schemas/flow-artifact-bundle.schema.json`,
- artifact entries with role, schema, required flag, derived flag, pipeline index and sources,
- conformance and JUnit coverage for the public artifact bundle.

Carried from v0.3.9:

- `TargetDecisionTraceAnalyzer`,
- `target-decision-trace-report.json`,
- `schemas/target-decision-trace-report.schema.json`,
- decision trace steps for execution plan, capability negotiation, execution readiness and target selection,
- target explanations for recommended, degraded and blocked targets,
- conformance and JUnit coverage for the target decision trace.

Carried from v0.3.8:

- `TargetSelectionAnalyzer`,
- `target-selection-report.json`,
- `schemas/target-selection-report.schema.json`,
- ranked target candidates with readiness, portability score, blocker count and warning count,
- conformance and JUnit coverage for reference target selection.

Carried from v0.3.7:

- `ExecutionReadinessAnalyzer`,
- `execution-readiness-report.json`,
- `schemas/execution-readiness-report.schema.json`,
- readiness fields `generationAllowed`, `productionReady`, `blockers`, `warnings` and `requiredActions`,
- conformance and JUnit coverage for Jenkins ready, GitHub Actions degraded and Tekton blocked behavior.

Carried from v0.3.6:

- `portabilityScore` on `capability-negotiation-report.json`,
- per-target `portabilityScore`,
- `portableCapabilities`,
- `targetSpecificCapabilities`,
- `blockingPortabilityIssues`,
- `requiredWorkarounds`,
- `schemas/capability-negotiation-report.schema.json` bumped to report v1.1,
- conformance and JUnit coverage for ExecutionPlan portability.

Carried from v0.3.5:

- `IntentDecisionAnalyzer`,
- `intent-decision-report.json`,
- `schemas/intent-decision-report.schema.json`,
- blocking decision checks for cleanup retention, database migration backup and production deploy approval,
- recommended decision checks for backup schedule timezone,
- conformance and JUnit coverage for the decision model.

Carried from v0.3.4:

- Capability Module Contract report via `./gradlew run --args="modules"`,
- module descriptor contract v1.2 with `secrets`, `requiredCapabilities` and `targetImplications`,
- removed module-owned `runtime`, `entrypoint`, `template` and `generators` from the public module contract,
- architecture guardrail `architecture.modules-do-not-own-target-rendering`,
- `schemas/capability-module-contract-report.schema.json`,
- conformance and JUnit coverage for capability module contracts,
- continued v0.3.2 safety/canonical execution plan behavior.

Carried from v0.3.2:

- first-class `SafetyPolicyValidator` for approval, dry-run, backup, rollback, change-ticket and destructive-operation gates,
- canonical lowercase `canonical-execution-plan.json` export for adapter/runtime consumers,
- schema coverage for `capability-negotiation-report.json`,
- adapter-facing YAML loader namespace with backward-compatible legacy loaders,
- stricter cleanup and Kubernetes maintenance scenario pack safety behavior,
- v0.3.2 conformance vectors for canonical execution plans and safety policy validation.

## 0.3.0-rc1.8.3 - Conformance stabilization hotfix

- Keeps functional conformance checks blocking.
- Keeps snapshot, schema and rendered-version drift checks blocking through `ConformanceRunner.summary.ok`.
- Adds explicit failure details to the JUnit bridge for core specification scenarios.
- Bumps Flow standard version to 0.3.0-rc1.8.3.

# Flow Core Prototype v0.3.0-rc1.8.3

## Secret rotation and conformance hotfix

This build fixes secret-rotation clarification behavior and makes default conformance focus on generated semantics. Exact golden snapshot comparison is still available with `FLOW_SNAPSHOT_STRICT=true`.

Flow is an AI-first, platform-neutral automation standard. It is not a Jenkins DSL and not a new general-purpose programming language. The main path is:

```text
Human / AI Intent
  -> Standard Intent Model
  -> Capability Validation
  -> Flow AST
  -> Execution Plan
  -> Target Compatibility Report
  -> Target Manifest
  -> Vendor Renderer / Runtime
```

The goal is to let humans describe automation intent, rules, risks and safety boundaries while Flow validates and maps that intent to different automation platforms.

## What v0.3.0-rc1.8.3 Adds

This release is a foundation-correction release. It keeps Flow aligned with the original goal: a standardization layer for automation intent, not another low-level parser-centered workflow DSL.

- Structured `IntentValue` model: maps/lists/secrets/refs/expressions remain structured after YAML loading.
- `IntentYamlLoader` preserves flow-style and block-style YAML without flattening complex values into JSON strings.
- `IntentDesignReport` includes convention assumptions so hidden defaults are visible to the user.
- Target manifests are node-preserving and consume `ExecutionPlan.nodes`, not only flattened `ExecutionPlan.tasks`.
- Target capability registry supports fine-grained feature keys such as `approval.inline`, `parallel.dag`, `loops.dynamic`, `secrets.runtime`.
- Standard Intent Catalog is moving toward executable capability contracts with required params, systems, lowering strategy and target implications.
- Tekton renderer now emits safer scripts with `#!/bin/sh` and `set -eu`.
- Version metadata is aligned to `0.3.0-rc1.8.3`.


## v0.3.0-rc1.8.3 AI Normalization Hardening

This release tightens the AI intent normalization path so the normalizer is not allowed to produce a convenient but invalid intent. The normalized deployment path is now tested through:

```text
ScenarioPackIntentNormalizer
  -> IntentCapabilityValidator
  -> IntentToAstPlanner
  -> FlowValidator
  -> FlowPlanner
```

Key corrections:

- The scenario-pack normalizer uses English command vocabulary only.
- Kubernetes deployment and verification no longer emit unsupported module parameters.
- The most common normalized Kubernetes deployment scenario must pass full Flow AST validation.
- Conformance includes a schema smoke check for public JSON artifacts.
- Gradle artifact version and Flow standard version are aligned exactly to `0.3.0-rc1.8.3`.


## v0.3.0-rc1.8.3 Scenario Packs

This release adds Scenario Packs, the first formal layer between natural-language AI normalization and the Standard Intent Model. The goal is to keep Flow aligned with its original purpose: a platform-neutral automation standard, not another low-level workflow DSL.

Current packs:

```text
deployment
backup-restore
data-sync
secret-rotation
incident-runbook
```

Useful commands:

```bash
./gradlew run --args="scenarios"
./gradlew run --args="scenarios --markdown"
./gradlew run --args="scenario deployment --examples"
```

Scenario pack output must either ask required clarification questions or pass the full validation pipeline: intent validation, AST lowering, Flow validation and execution-plan creation.

## Commands

Run conformance:

```bash
./gradlew run --args="conformance"
```

Generate a Jenkins end-to-end artifact bundle:

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target jenkins --strict --render --out generated/jenkins"
```

Generate a GitHub Actions bundle:

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target github-actions --render --out generated/github"
```

Generate a partial Tekton bundle:

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target tekton --render --out generated/tekton"
```

Expected exported artifacts:

```text
standard-version.txt
standard-diagnostic-catalog.json
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
reference-corpus-index.json
negative-conformance-corpus.json
target-conformance-profile.json
standard-index.json
conformance-suite.json
flow-standard-draft.json
target-manifest.json
Jenkinsfile | github-actions.yml | tekton-pipeline.yaml
flow-artifact-bundle.json
```

Conformance export:

```bash
./gradlew run --args="conformance --out generated/conformance"
```

This writes:

```text
conformance-manifest.json
standard-diagnostic-catalog.json
```

Diagnostic catalog export:

```bash
./gradlew run --args="diagnostics --out generated/diagnostics"
```

This writes:

```text
standard-diagnostic-catalog.json
```

Release profile export:

```bash
./gradlew run --args="release-profile --out generated/release-profile"
```

This writes:

```text
standard-release-profile.json
```

Standard draft export:

```bash
./gradlew run --args="standard-draft --out generated/standard-draft"
```

This writes the public draft reports including:

```text
standard-freeze-report.json
compatibility-policy.json
reference-corpus-index.json
negative-conformance-corpus.json
target-conformance-profile.json
standard-index.json
conformance-suite.json
flow-standard-draft.json
```

## Design Guardrail

Low-level `.flow` syntax is only one representation. The project must stay aligned with the original mission: a standardization layer over DevOps automation platforms, not another vendor-specific workflow syntax.


### New in v0.3.0-rc1.8.3

This release adds the first executable **Standard Intent Catalog** and an **Intent Design Report**.

The intent command now surfaces architecture-level requirements before lowering:

```bash
./gradlew run --args="intent examples/intent/build-test-deploy.intent.yaml --target jenkins --strict --render --out generated/jenkins"
```

Additional discovery commands:

```bash
./gradlew run --args="catalog"
./gradlew run --args="catalog --markdown"
./gradlew run --args="targets"
./gradlew run --args="modules"
```

The project direction remains: users describe intent and architecture; Flow handles validation, planning, compatibility and target rendering.



## v0.3.0-rc1.8.3 Foundation Corrections

This release corrects the direction back toward Flow as an AI-first standardization layer, not a low-level parser demo. Main changes:

- Structured IntentValue model keeps YAML maps/lists as data instead of JSON strings.
- Intent Design Report now includes convention assumptions.
- Target manifests are node-preserving and consume ExecutionPlan.nodes, not only flattened tasks.
- Target capability registry supports fine-grained feature keys such as approval.inline, parallel.dag and secrets.runtime.
- Standard Intent Catalog is moving toward executable capability contracts.
- Tekton renderer now emits scripts with shebang and set -eu.

Remaining WIP: richer target renderers, external execution conformance harnesses, richer Tekton/GitHub/Jenkins feature mappings, and complete JSON Schema coverage for the structured intent value model.

## AI Intent Normalization CLI

`0.3.0-rc1.8.3` adds a provider-neutral normalization command. The built-in implementation is scenario-pack based and deterministic; real LLM integrations should be adapters that return the same validated model.

```bash
./gradlew run --args='normalize "Deploy application billing-api to Kubernetes. Require approval in production. Verify health after deploy and rollback on failure." --target jenkins --strict --render --out generated/ai-deploy'
```

File input:

```bash
./gradlew run --args='normalize --file examples/ai-normalization/deploy-request.txt --target github-actions --render'
```

The command prints and optionally exports:

- AI normalization report,
- normalized intent,
- intent capability validation report,
- intent design report,
- generated AST,
- execution plan,
- compatibility report,
- target manifest,
- rendered target output.


## 0.3.0-rc1.8.3

- Fixed public JSON serialization to omit null optional fields so schema conformance does not fail on optional step descriptions.
- Kept schema validation blocking; this is a real fix, not an advisory bypass.
