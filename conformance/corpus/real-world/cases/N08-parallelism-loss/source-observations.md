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
This case is a permanent regression boundary: canonical lowering must preserve the diamond, while the negative mutation explicitly adds B -> C and must be rejected as serialized parallelism.
