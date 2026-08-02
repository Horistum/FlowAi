# Source observations

## Observed behavior
The source emits active LTS versions as JSON and expands a downstream matrix from that output.

## Reconstructed intent
Preserve the value relation while explicitly reporting that current Flow cannot represent the bounded runtime matrix topology.

## Invariants
- The producer value reaches the matrix consumer.
- Matrix cardinality is derived at runtime from the producer output.
- The workflow is not approximated as one ordinary task.

## Ambiguities
The output is bounded JSON but its cardinality is not known until execution; this differs from arbitrary pipeline upload but still exceeds the current plan model.
