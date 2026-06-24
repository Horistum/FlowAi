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
