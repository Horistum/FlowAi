# Flow Agent Generated Context

Current version: 0.7.5
Next version: 0.7.6
Next item: Reference Corpus Expansion I
Purpose: Expand real-world automation intent coverage using practical DevOps and IT automation scenarios.

## agent-contract.md

# Flow Roadmap Development Agent Contract

## Mission

The Flow Roadmap Development Agent incrementally evolves Flow Core according to the approved roadmap,
architecture constitution, conformance gates, quality gates, and release state.

Flow is an AI-first standardization layer for DevOps and IT automation.

Flow is not:

- a runtime executor,
- an SDK-first platform,
- a plugin lifecycle framework,
- a target-specific public DSL,
- a Jenkins-specific language,
- a generic workflow engine without a standard contract.

## Primary Responsibilities

The agent must:

1. Read `.flow-agent/architecture-constitution.md` before proposing any change.
2. Read `.flow-agent/roadmap.yaml` before selecting a development step.
3. Read `.flow-agent/release-state.yaml` before modifying the project.
4. Implement only the next approved roadmap item unless the user explicitly overrides it.
5. Create a work package before changing code.
6. Explain the architectural purpose of each change.
7. Prefer minimal, reviewable patches over broad rewrites.
8. Add or update tests for every behavior change.
9. Add or update conformance vectors when public behavior or standard surface changes.
10. Update documentation for every public contract change.
11. Run offline validation whenever possible.
12. Produce a release report for every version.
13. Report all limitations honestly.

## Hard Constraints

The agent must not:

- introduce runtime workflow execution into Flow Core,
- introduce SDK-first architecture,
- introduce plugin lifecycle management into the standard,
- introduce target-specific public syntax,
- introduce Jenkins-specific language concepts into the public standard,
- bend production code only to make tests pass,
- add undocumented compatibility aliases,
- rewrite large areas without explicit architectural justification,
- hide validation failures,
- claim full validation when only partial validation was performed.

## Development Rule

Every version must do at least one of the following:

1. Increase the ability to process real automation intent.
2. Refine the public standard contract.
3. Improve compatibility verification for target platforms.
4. Remove or reduce technical debt.
5. Strengthen safety, conformance, or architectural quality.

If a proposed change does none of these, it must be rejected.

## Required Output Per Version

Each version must produce:

- a work package,
- a patch summary,
- test updates,
- conformance updates when applicable,
- documentation updates when applicable,
- validation results,
- a release report,
- a release-state update.


## architecture-constitution.md

# Flow Architecture Constitution

## Core Identity

Flow is an AI-first standardization layer for DevOps and IT automation.

Its purpose is to separate:

- human intent,
- automation rules,
- operational risks,
- safety boundaries,
- platform capabilities,
- target projection details.

Flow must allow a human and AI to describe automation intent in a stable, platform-neutral way,
then validate and plan that intent before projecting it to target platforms.

## Primary Architecture Chain

The canonical conceptual chain is:

```text
Human / AI Intent
  -> Flow Source
  -> Parser
  -> Flow AST
  -> Validator
  -> Planner
  -> Flow Execution Plan
  -> Target Capability Negotiation
  -> Target Generator / Projection
  -> Target Platform Manifest
```

## Ownership Boundaries

### Flow Core Owns

- standard model,
- parser and AST,
- validator,
- planner,
- execution plan contract,
- safety policy model,
- capability requirements,
- conformance vectors,
- reference intent corpus,
- standard documentation.

### Target Generators Own

- Jenkins projection,
- GitHub Actions projection,
- Tekton projection,
- Argo Workflows projection,
- target-specific manifest rendering,
- target-specific limitations,
- target capability mapping.

### Runtime Systems Own

- actual workflow execution,
- job scheduling,
- credential runtime behavior,
- platform lifecycle,
- operational state,
- retries performed by the target platform.

## Immutable Principles

These principles are stable unless a major public version explicitly replaces the constitution:

1. Flow is not Jenkins-specific.
2. Flow is not a runtime executor.
3. Flow is not an SDK-first architecture.
4. Flow is not a plugin lifecycle framework.
5. Flow public syntax must remain target-neutral.
6. Flow must validate before generating target output.
7. Flow must preserve safety boundaries explicitly.
8. Flow must prefer standard contracts over implementation convenience.
9. Flow must avoid self-referential governance that does not measure behavior, quality, or drift.
10. Flow must reduce technical debt rather than carry it forward.

## Forbidden Architectural Drift

A change is architectural drift if it:

- places target-specific behavior into the public language,
- requires Flow Core to execute workflows,
- makes target generators the source of standard semantics,
- introduces public syntax for a single platform,
- duplicates the same standard concept in multiple independent models,
- adds compatibility shortcuts without a clear migration or validation purpose,
- replaces conformance with examples only,
- adds tests that verify implementation details but not standard behavior.

## Design Preference

Prefer:

- small standard contracts over large convenience APIs,
- explicit validation over implicit guessing,
- capability negotiation over target assumptions,
- execution plans over direct execution,
- scenario corpus over isolated examples,
- negative tests for forbidden behavior,
- release reports over undocumented change history.

## Quality Principle

A test passing is not enough.

A change is acceptable only if:

- the architecture still matches this constitution,
- the behavior is covered by tests or conformance vectors,
- the public contract remains clear,
- the implementation does not hide debt,
- the release report states what changed and what did not.


## roadmap.yaml

project: Flow Core
roadmapVersion: 1
currentTrack: "0.7.x-to-1.0.0"

rules:
  - "Follow versions in order unless explicitly overridden."
  - "Every version must have a measurable purpose."
  - "Every version must preserve the architecture constitution."
  - "No version may introduce runtime execution into Flow Core."
  - "No version may introduce target-specific public DSL syntax."
  - "No version may bend code only to satisfy tests."

roadmap:
  - version: "0.7.3"
    name: "Standard Model Collapse"
    purpose: "Collapse duplicated or conflicting standard model paths into a single canonical source of truth."
    type: "debt-reduction"
    status: "completed"
    requiredOutcome:
      - "Single canonical standard model path."
      - "Removed or deprecated duplicated semantic definitions."
      - "No public syntax expansion."
      - "Conformance remains compatible."

  - version: "0.7.4"
    name: "Architecture Delta Analyzer"
    purpose: "Measure architectural changes against the approved constitution and detect drift."
    type: "architecture-quality"
    status: "completed"
    requiredOutcome:
      - "Architecture delta analyzer."
      - "Forbidden-direction checks."
      - "Conformance vector coverage."
      - "Release report integration."

  - version: "0.7.5"
    name: "Purpose Coverage Ratio"
    purpose: "Measure how much of the original Flow purpose is behaviorally covered."
    type: "purpose-verification"
    status: "completed"
    requiredOutcome:
      - "Purpose coverage model."
      - "Accepted and rejected purpose scenarios."
      - "Coverage report."
      - "Tests proving coverage calculation."

  - version: "0.7.6"
    name: "Reference Corpus Expansion I"
    purpose: "Expand real-world automation intent coverage using practical DevOps and IT automation scenarios."
    type: "intent-coverage"
    status: "next"
    requiredOutcome:
      - "Additional reference intent scenarios."
      - "Expected capabilities and entities for each scenario."
      - "Negative scenarios for unsafe or unsupported intent."
      - "No custom fallback lowering."
      - "No production auto-approval."
      - "Updated tests and conformance vectors."

  - version: "0.7.7"
    name: "Scenario Pack Quality Gates"
    purpose: "Validate scenario pack consistency, usefulness, safety metadata, and non-decorative coverage."
    type: "quality-gate"
    status: "planned"
    requiredOutcome:
      - "Scenario pack validation rules."
      - "Negative corpus checks."
      - "Required metadata checks."
      - "Scenario quality conformance vector."

  - version: "0.8.0"
    name: "Core Standard Contract Freeze"
    purpose: "Freeze the public core standard contract before target compatibility expansion."
    type: "contract-freeze"
    status: "planned"
    requiredOutcome:
      - "Frozen core standard contract."
      - "Compatibility policy."
      - "Breaking-change policy."
      - "Public standard surface report."

  - version: "0.8.1"
    name: "Target Capability Matrix"
    purpose: "Define a stable matrix of target capabilities across supported platforms."
    type: "capability-contract"
    status: "planned"
    requiredOutcome:
      - "Capability matrix model."
      - "Target capability definitions."
      - "Unsupported capability representation."
      - "Tests for known targets."

  - version: "0.8.2"
    name: "Target Negotiation Report"
    purpose: "Produce reports explaining how intent maps or fails to map to target capabilities."
    type: "capability-negotiation"
    status: "planned"
    requiredOutcome:
      - "Negotiation report model."
      - "Supported, degraded, and unsupported capability outcomes."
      - "Clear rejection reasons."

  - version: "0.8.3"
    name: "Planner Capability Constraints"
    purpose: "Ensure the planner respects target capability constraints before projection."
    type: "planner-hardening"
    status: "planned"
    requiredOutcome:
      - "Planner capability checks."
      - "No unsupported lowering."
      - "Negative tests for unsupported targets."

  - version: "0.8.4"
    name: "Safety Boundary Hardening"
    purpose: "Strengthen safety behavior for destructive, production, credential, and rollback-sensitive operations."
    type: "safety"
    status: "planned"
    requiredOutcome:
      - "Safety boundary model hardening."
      - "Production approval guarantees."
      - "Negative tests for unsafe lowering."

  - version: "0.9.0"
    name: "Generator Projection Contract"
    purpose: "Define the stable contract between ExecutionPlan and target generators."
    type: "projection-contract"
    status: "planned"
    requiredOutcome:
      - "Generator projection contract."
      - "Manifest rendering boundaries."
      - "Target-specific behavior kept outside public syntax."

  - version: "0.9.1"
    name: "Jenkins/GitHub/Tekton Projection Stability"
    purpose: "Stabilize projections for the main supported target platforms."
    type: "target-stability"
    status: "planned"
    requiredOutcome:
      - "Stable Jenkins projection smoke tests."
      - "Stable GitHub Actions projection smoke tests."
      - "Stable Tekton projection smoke tests."
      - "No public target-specific DSL."

  - version: "0.9.5"
    name: "End-to-End Standard Scenarios"
    purpose: "Prove full standard flow across realistic end-to-end automation scenarios."
    type: "e2e-standard"
    status: "planned"
    requiredOutcome:
      - "End-to-end reference scenarios."
      - "Intent to AST to plan to projection validation."
      - "Release readiness report."

  - version: "1.0.0"
    name: "Public Flow Standard"
    purpose: "Publish the first stable Flow standard contract."
    type: "public-standard"
    status: "planned"
    requiredOutcome:
      - "Stable public standard."
      - "Compatibility statement."
      - "Conformance suite."
      - "Reference documentation."


## release-state.yaml

project: Flow Core
stateVersion: 1

currentVersion: "0.7.5"
currentPackage: "flow-core-prod-v0_7_5.zip"
nextExpectedVersion: "0.7.6"
activeStandardVersion: "0.7.5"

repositoryRules:
  sourceLanguage: "English"
  chatLanguage: "User-preferred language allowed outside repository artifacts"

architectureConstraints:
  noRuntimeExecutor: true
  noSdkFirstArchitecture: true
  noPluginLifecycle: true
  noTargetSpecificPublicDsl: true
  noJenkinsSpecificLanguage: true
  noTestOnlyHacks: true

qualityPrinciples:
  - "Do not bend code only to make tests pass."
  - "Prefer small reviewable changes."
  - "Every public behavior change must have tests."
  - "Every public contract change must have documentation."
  - "Every release must include a release report."
  - "Every architectural change must be justified."
  - "Reduce technical debt instead of carrying it forward."

lastKnownValidation:
  fullOfflineGradle: "unknown"
  targetedCompilation: "unknown"
  conformance: "unknown"
  notes:
    - "Update this file after each completed version."


## quality-gates.yaml

qualityGates:
  architecture:
    - id: "architecture-purpose-check"
      rule: "Every change must support the original Flow purpose."
      severity: "blocker"

    - id: "no-runtime-executor"
      rule: "Flow Core must not execute workflows."
      severity: "blocker"

    - id: "no-target-specific-public-dsl"
      rule: "Public Flow syntax must not expose target-specific constructs."
      severity: "blocker"

    - id: "single-source-of-truth"
      rule: "Standard concepts must not have multiple independent definitions."
      severity: "major"

    - id: "target-boundary-preserved"
      rule: "Target-specific behavior must remain in target generators or projection layers."
      severity: "blocker"

  code:
    - id: "minimal-diff"
      rule: "Prefer minimal, reviewable patches."
      severity: "major"

    - id: "no-large-rewrite-without-justification"
      rule: "Large rewrites require explicit architectural justification."
      severity: "blocker"

    - id: "no-test-only-hacks"
      rule: "Do not modify production code only to satisfy tests."
      severity: "blocker"

    - id: "clear-errors"
      rule: "Validation failures must produce useful, stable error codes or explanations."
      severity: "major"

  tests:
    - id: "behavior-covered"
      rule: "Every new public behavior must be covered by tests or conformance vectors."
      severity: "blocker"

    - id: "negative-scenarios"
      rule: "Forbidden behavior must have negative tests."
      severity: "major"

    - id: "no-fallback-lowering"
      rule: "The planner must not silently lower unsupported or unknown intent through fallback behavior."
      severity: "blocker"

  docs:
    - id: "public-contract-docs"
      rule: "Every public contract change must be documented."
      severity: "major"

    - id: "release-report"
      rule: "Every version must include a release report."
      severity: "major"


## forbidden-directions.yaml

forbiddenDirections:
  - id: "runtime-executor"
    description: "Flow Core must not execute workflows or become a runtime engine."
    severity: "blocker"

  - id: "sdk-first-architecture"
    description: "Flow Core must not be organized primarily as an SDK product."
    severity: "blocker"

  - id: "plugin-lifecycle"
    description: "Flow Core must not introduce plugin lifecycle management as part of the public standard."
    severity: "blocker"

  - id: "target-specific-public-dsl"
    description: "Flow public syntax must not expose Jenkins, GitHub Actions, Tekton, Argo, or other platform-specific constructs."
    severity: "blocker"

  - id: "jenkins-specific-language"
    description: "Flow must not become a Jenkins-specific DSL."
    severity: "blocker"

  - id: "test-only-hack"
    description: "Implementation must not be bent only to satisfy tests."
    severity: "blocker"

  - id: "duplicated-standard-model"
    description: "The same standard concept must not have multiple independent sources of truth."
    severity: "major"

  - id: "undocumented-compatibility-alias"
    description: "Compatibility aliases must not be added without documentation and migration reason."
    severity: "major"

  - id: "self-referential-governance"
    description: "Governance features must measure behavior, quality, drift, compatibility, or coverage. Ceremony-only controls are not allowed."
    severity: "major"


## runbook.md

# Flow Agent Runbook

## Required Development Cycle

The agent must use this process for every version.

### 1. Load State

Read:

- `.flow-agent/agent-contract.md`
- `.flow-agent/architecture-constitution.md`
- `.flow-agent/roadmap.yaml`
- `.flow-agent/release-state.yaml`
- `.flow-agent/quality-gates.yaml`
- `.flow-agent/forbidden-directions.yaml`

### 2. Select Roadmap Item

Use the roadmap item with `status: next` unless explicitly instructed otherwise.

### 3. Create Work Package

Create a work package under:

```text
.flow-agent/work-packages/
```

Use `.flow-agent/work-package-template.yaml`.

### 4. Inspect Existing Code

Inspect the existing implementation before changing files.
Do not guess structure from memory.

### 5. Architecture Check

Reject or redesign the change if it violates any immutable principle or forbidden direction.

### 6. Patch Plan

Create a minimal patch plan listing:

- files to inspect,
- files to change,
- files to add,
- tests to add,
- conformance vectors to update,
- documentation to update.

### 7. Implement

Apply the smallest safe change that satisfies the work package.

### 8. Validate

Run the strongest available offline validation.
If full validation is not possible, run targeted validation and report limitations.

### 9. Report

Create a release report under:

```text
.flow-agent/reports/
```

### 10. Update State

Update `.flow-agent/release-state.yaml` only after validation.

