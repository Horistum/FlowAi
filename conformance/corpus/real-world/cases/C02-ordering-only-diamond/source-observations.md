# Source observations

## Observed behavior
The source declares a diamond DAG with B and C both depending on A and D depending on both siblings.

## Reconstructed intent
Preserve the fan-out/fan-in topology without inventing data continuity.

## Invariants
- A completes before B and C.
- B and C are siblings and must not depend on each other.
- D starts only after both B and C complete.

## Ambiguities
The Standard Intent Model represents the diamond through explicit ordering edges; sibling independence is the absence of B -> C and C -> B, not an implicit lexical-order contract.
