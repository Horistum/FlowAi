# Source observations

## Observed behavior
The pinned Buildkite source discovers task directories at runtime, emits one step for each directory and emits a deployment step only when the branch is main.

## Reconstructed intent
Preserve the visible deployment as infrastructure state-changing intent while reporting that arbitrary runtime-generated fan-out has no explicit current ExecutionPlan representation.

## Invariants
- Runtime-discovered plan cardinality is not replaced by one static task.
- The conditional deployment remains visible as a state-changing operation.
- Missing dynamic-plan representation blocks executable classification.

## Ambiguities
The source deployment command is intentionally minimal and does not identify a concrete infrastructure provider. C0.1 proves state-changing intent and unsupported plan construction, not provider readiness.
