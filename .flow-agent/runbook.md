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

Follow the [active-roadmap route](README.md#active-roadmap-route) into the post-toolchain and recovery documents named by their existing pointers. The root index and generated context preserve a completed earlier stream; read the selected recovery work package separately.

### 2. Select Roadmap Item

Use the selected authority's current decision and activation gates. A `status: next` item is relevant only within the selected stream; a completed root-stream handoff must not override an active recovery decision. Read the current bounded correction before continuing the broader milestone. Do not activate AR-07 or resume EF-09 from a documentation change.

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
