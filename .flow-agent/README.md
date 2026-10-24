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

## Active roadmap route

Use this navigation route to select work; it introduces no new roadmap authority:

1. Read the constitution and [release state](release-state.yaml), then the [root roadmap index](roadmap.yaml). The root's completed stream fields and `primaryRoadmapStream` preserve the earlier handoff; they do not select today's recovery work.
2. Follow the existing `postToolchainRoadmap` pointer to the [post-toolchain roadmap](roadmap-post-toolchain.yaml). Its `currentDecision.nextItemRoadmap` selects the active recovery roadmap.
3. Read the [recovery roadmap](roadmap-architecture-recovery.yaml): `currentDecision` owns milestone selection, and `correctiveWorkPackage` / `systemAuditFollowup` identify the current bounded correction. Read that work package and its report before editing.
4. Cross-check the release state's recovery fields and the actual merged revision. Completed items and historical `decisionBasis` receipts are evidence of their recorded boundaries, not new activation decisions.

Recovery currently keeps AR-06 active, AR-07 planned and EF-09 paused. A-05's documentation correction does not activate migration cleanup or resume the paused stream. After this correction, select further AR-06 work from its remaining evidence gaps.

The existing Python context generator reports the completed root-stream lifecycle and embeds its indexed documents; it does not independently select the post-toolchain recovery correction. Follow the route above even when that generated summary says `completed`. Keep historical selectors and acceptance receipts intact.

For implemented module and compiler boundaries, see the [current architecture](../docs/ARCHITECTURE.md).

## Mandatory Process

Every development step must follow this cycle:

1. Read the agent contract.
2. Read the architecture constitution.
3. Read the current release state.
4. Follow the active-roadmap route above and select the authorized work item.
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
