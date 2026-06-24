# Flow v0.3.6 - ExecutionPlan Portability

v0.3.6 keeps the project on the core Flow path:

```text
Human / AI intent
  -> Standard Intent Model
  -> decision, safety and capability validation
  -> ExecutionPlan
  -> target capability negotiation
  -> target manifest / renderer
```

This release does not add syntax, a runtime SDK, module execution hooks or new target renderers. It extends the existing capability negotiation report so the ExecutionPlan can be evaluated as a portable standard artifact.

## New report fields

`capability-negotiation-report.json` now exposes:

- `portabilityScore`: normalized score from `0.0` to `1.0` across registered targets,
- per-target `portabilityScore`,
- `portableCapabilities`: capabilities fully supported by every registered target,
- `targetSpecificCapabilities`: capabilities that are only partial, runtime-dependent or unsupported on at least one registered target,
- `blockingPortabilityIssues`: unsupported capabilities that block portability to a target,
- `requiredWorkarounds`: partial or runtime-dependent capabilities that require explicit target handling.

## Scoring rule

Each required capability is scored per target:

- supported: `1.0`,
- partial: `0.5`,
- requires runtime: `0.25`,
- unsupported: `0.0`.

The target score is the average across required capabilities. The plan score is the average across registered target scores.

## Standardization purpose

The report is intentionally attached to the ExecutionPlan, not to the original natural-language request. A target adapter should be able to decide whether a plan is safe and portable without re-reading the original intent.
