# ADR-0001: AST Data and Orchestration Boundary

## Status

Accepted

## Context

Flow needs enough structure to represent common automation intent: conditions, loops, bounded data transformations, validation, aggregation and orchestration. These constructs are useful for portable automation plans, but they also create a risk that Flow grows into a general-purpose programming language.

The project constitution says Flow is an AI-first standardization layer for DevOps and IT automation. It is not a runtime executor, SDK framework, plugin framework or target-specific workflow language.

## Decision

Flow AST supports declarative orchestration and bounded data operations only.

Flow AST does not support arbitrary general-purpose computation.

The following boundaries apply:

- `TransformNode` may describe bounded data selection or mapping, but it must not execute system actions.
- `AggregateNode` may describe deterministic aggregation, but it must not create side effects.
- `ForNode` may describe portable iteration intent, but it must not become a target-owned execution loop API.
- `MatchNode` may describe explicit branching, but it must not hide unsupported semantics through silent fallback.
- AST nodes must remain platform-neutral and must not contain target template syntax.
- Any future expansion that adds language-like power requires an Architecture Decision and conformance guardrail.

## Consequences

This keeps Flow expressive enough for real automation while preserving the standard boundary. It also creates a review point before additional programming-language features are added, because apparently even a standard can accidentally grow tentacles if nobody watches it.
