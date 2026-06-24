# Flow Roadmap Development Agent

This directory defines the repository-bound development agent for Flow Core.

The agent is not a runtime executor, SDK, plugin framework, or target-specific workflow engine.
It is a controlled development mechanism for evolving Flow Core according to the approved roadmap,
architecture constitution, quality gates, and release state.

## Purpose

The agent exists to keep Flow development aligned with the original project purpose:

> Flow is an AI-first standardization layer for DevOps and IT automation. It separates human intent,
> rules, risks, and safety boundaries from target-specific workflow syntax and platform lifecycle details.

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
