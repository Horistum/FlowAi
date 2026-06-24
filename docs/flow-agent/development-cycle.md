# Flow Agent Development Cycle

## Overview

The Flow Agent development cycle is:

```text
Roadmap Item
  -> Work Package
  -> Architecture Delta Check
  -> Patch Plan
  -> Code
  -> Tests
  -> Conformance
  -> Documentation
  -> Validation
  -> Release Report
  -> Release State Update
```

## Human Role

The human approves direction and reviews the release.

## AI Agent Role

The AI agent proposes, implements, tests, validates, and reports within strict architectural boundaries.

## CI Role

CI verifies that the repository structure, tests, and conformance checks remain valid.

## Non-Goal

The agent is not allowed to auto-merge or release without explicit human approval.
