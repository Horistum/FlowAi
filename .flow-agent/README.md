# Horistum Roadmap Development Agent

<a id="flow-roadmap-development-agent"></a>

This directory defines the repository-bound development agent for Horistum. Its technical Core retains the name Flow Core.

The agent is not a runtime executor, SDK, plugin framework, or target-specific workflow engine.
It is a controlled development mechanism for evolving Flow Core according to the approved roadmap,
architecture constitution, quality gates, and release state.

## Purpose

The agent exists to keep Horistum development aligned with the original project purpose, quoted here with its retained technical name:

> Flow is an AI-first standardization layer for DevOps and IT automation. It separates human intent,
> rules, risks, and safety boundaries from target-specific workflow syntax and platform lifecycle details.

## Product identity

Use Horistum as the product name in current presentation material. Retain `FlowAi`, `Flow`, `flow` and `flowlang` where they identify existing code, tooling, contracts or historical evidence. This is intentional compatibility, not unfinished cleanup. See [Product identity and retained technical names](../docs/PRODUCT_IDENTITY.md).

The `.flow-agent/` path, architecture constitution, roadmap, release state and validation rules remain unchanged. The naming decision does not authorize a namespace migration, a contract version change, or a new roadmap stream.

## Mandatory Process

Every development step must follow this cycle:

1. Read the agent contract.
2. Read the architecture constitution.
3. Read the current release state.
4. Select the next roadmap item.
5. Create a work package.
6. Run architecture and forbidden-direction checks.
7. Plan the smallest safe patch.
8. Implement code, tests, conformance vectors, and documentation.
9. Run offline validation.
10. Produce a release report.
11. Update release state only after validation.

## Source Language Rule

All repository text must be English.
Chat communication may use Czech or another user-preferred language.
