# Flow v0.3.12 - Target Adapter Contract

v0.3.12 keeps Flow on the standardization path:

```text
ExecutionPlan
  -> target reports
  -> target adapter contract
  -> target manifest / rendered artifact
```

This release does not add an SDK, runtime executor, plugin system, new syntax or new renderer. It defines the public boundary that a target adapter must obey.

## Public artifacts

v0.3.12 adds:

- `target-adapter-contract.json`,
- `adapter-diagnostics.json`.

## Allowed adapter inputs

Target adapters may consume:

- `execution-plan.json`,
- `canonical-execution-plan.json`,
- `execution-readiness-report.json`,
- `target-selection-report.json`,
- `target-decision-trace-report.json`.

Target adapters must not consume or reinterpret:

- human/AI intent,
- `normalized-intent.json`,
- `intent-design-report.json`,
- `intent-decision-report.json`,
- `intent-capability-validation-report.json`.

## Required invariants

- `ADAPTER_MUST_NOT_READ_INTENT`,
- `ADAPTER_MUST_PRESERVE_PLAN_NODE_IDS`,
- `ADAPTER_MUST_RESPECT_READINESS`,
- `ADAPTER_SHOULD_EMIT_DIAGNOSTICS`.

## Why this belongs in Flow

Adapters are necessary, but they must not become a second planning layer. Flow planning and safety decisions happen before the adapter boundary. The adapter receives validated standard artifacts and emits target-specific artifacts plus diagnostics.
