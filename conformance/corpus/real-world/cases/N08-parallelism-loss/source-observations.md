# Source observations

## Observed behavior
The source requires sibling branches B and C and a final fan-in at D.

## Reconstructed intent
Use the case as an adversarial guard against lowering or target materialization that converts the diamond into a chain.

## Invariants
- B and C remain unordered siblings.
- A target or lowering path must not serialize required parallel branches silently.
- Loss of fan-in or sibling parallelism blocks acceptance.

## Ambiguities
The current Standard Intent lowering introduces source-order dependencies, which this case must expose rather than bless.
