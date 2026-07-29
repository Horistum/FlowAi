# Source observations

## Observed behavior
The valid source binds a downstream artifact input to a concrete upstream artifact output.

## Reconstructed intent
Mutate the reconstructed intent so the consumer references an output no step produces.

## Invariants
- Every consumed named value has exactly one producer.
- A missing value fails before target selection.
- The diagnostic identifies a producer-to-consumer integrity failure.

## Ambiguities
The external source itself is valid; invalidity is introduced intentionally by the corpus case.
