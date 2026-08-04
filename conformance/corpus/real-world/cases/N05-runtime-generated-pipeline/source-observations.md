# Source observations

## Observed behavior
The pinned Buildkite source discovers task directories at runtime, emits one test step for each directory and conditionally emits a deployment step when the branch is main.

## Reconstructed intent
Preserve the request to generate a delivery pipeline from runtime discovery while reporting that arbitrary generated cardinality and structure have no explicit current ExecutionPlan representation.

## Invariants
- Runtime-discovered plan cardinality is not replaced by one fabricated static task list.
- The source remains software-delivery evidence and is not promoted to infrastructure provisioning.
- Missing dynamic-plan representation blocks executable classification.

## Ambiguities
The generated deployment command is only `echo Deploy!`; it does not identify an application, infrastructure resource or provider. C0.1 therefore proves unsupported runtime construction, not a concrete deployment or infrastructure mutation.
