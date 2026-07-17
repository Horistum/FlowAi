# Flow Architecture Constitution

## Core Identity

Flow is an AI-first standardization layer for universal automation intent.

Its purpose is to separate:

- human or AI intent,
- canonical semantic meaning,
- automation rules and effects,
- operational risks and safety boundaries,
- control and continuity requirements,
- implementation capabilities,
- concrete projection details.

Flow must allow a human or AI to describe automation intent in a stable, platform-neutral way, then validate and plan that intent before any adapter is considered.

## Primary Architecture Chain

The canonical conceptual chain is:

```text
Human / AI Intent
  -> Flow Source
  -> Parser
  -> Canonical Intent
  -> Semantic Validation
  -> Effects, Policy and Control Analysis
  -> Universal Execution Plan
  -> Materialization Negotiation
  -> Abstract Topology Satisfaction
  -> Adapter Binding
  -> Concrete Target Artifact
```

## Ownership Boundaries

### Flow Core Owns

- canonical intent and semantic capability meaning,
- parser and AST,
- semantic effects and state transitions,
- dependency and continuity requirements,
- safety and policy requirements,
- control requirements,
- execution plan contracts,
- materialization authority,
- abstract execution topology requirements,
- universal diagnostics and public contracts.

### Adapter Implementations Own

- concrete capability bindings,
- concrete topology evidence,
- target-specific limitations,
- concrete artifact generation and serialization,
- implementation-specific expression and value rules.

### Conformance Owns

- cross-domain semantic adequacy,
- falsifying negative cases,
- abstract topology behavior,
- semantic equivalence rules,
- independent adapter certification.

### Runtime Systems Own

- actual workflow execution,
- scheduling and operational lifecycle,
- runtime credentials and state,
- runtime retries and concurrency behavior.

## Immutable Principles

These principles are stable unless a major public version explicitly replaces the constitution:

1. Flow is not a runtime executor.
2. Flow is not an SDK-first architecture.
3. Flow is not a plugin lifecycle framework.
4. Flow public syntax must remain target-neutral.
5. Flow Core semantics are not defined by target adapters.
6. Flow must validate before generating concrete output.
7. Flow must preserve safety, control and continuity requirements explicitly.
8. Flow must prefer universal contracts over implementation convenience.
9. Flow must support automation domains beyond software delivery.
10. Flow must avoid self-referential governance that does not measure behavior, quality or drift.
11. Flow must reduce technical debt rather than carry it forward.

## Roadmap Ownership Rule

The project roadmap is split into Core, adapter and conformance streams.

- Core roadmap items define universal meaning and may not contain concrete platform or implementation-tool scope.
- Adapter roadmap items consume frozen Core contracts and may not redefine them.
- Conformance roadmap items prove universal invariants and certify adapter preservation separately.
- Each stream may advance independently, but no stream may bypass another stream's declared contract boundary.

Every Core roadmap item must declare a universal invariant, forbidden scope and completion evidence. A successful concrete artifact is not evidence that a universal Core invariant is complete.

## Forbidden Architectural Drift

A change is architectural drift if it:

- places implementation-specific behavior into universal semantics,
- makes a concrete platform or adapter the source of standard meaning,
- requires Flow Core to execute workflows,
- introduces public syntax for one implementation,
- duplicates the same standard concept in multiple independent models,
- silently drops or invents intent information,
- treats ordering as data or state continuity,
- treats action support as scenario executability,
- adds compatibility shortcuts without explicit evidence,
- replaces conformance with examples only,
- adds tests that verify syntax but not standard behavior.

## Design Preference

Prefer:

- small universal contracts over convenience APIs,
- explicit validation over implicit guessing,
- semantic effects over module-name inference,
- typed requirements over raw runtime instructions,
- abstract topology satisfaction over adapter assumptions,
- execution plans over direct execution,
- cross-domain corpus over delivery-only examples,
- negative tests for forbidden behavior,
- bounded reports over undocumented change history.

## Quality Principle

A test passing is not enough.

A change is acceptable only if:

- the architecture still matches this constitution,
- the declared universal invariant is behaviorally covered,
- the public contract remains clear,
- the implementation does not hide debt,
- the stream ownership boundary remains intact,
- the release report states what changed and what did not.
