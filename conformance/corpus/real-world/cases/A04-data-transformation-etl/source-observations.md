# Source observations

## Observed behavior
The pinned Airflow TaskFlow example extracts a dictionary, transforms it into an order total and passes that named value to a load task.

## Reconstructed intent
Preserve extract, transform and load as target-neutral data-transformation actions with explicit value-producing and value-consuming relations.

## Invariants
- Extracted order data reaches transform as a named value.
- The transformed total reaches load as a separate named value.
- The three operations remain canonical data-transformation semantics rather than opaque runtime text.

## Ambiguities
The example prints the final value rather than persisting it. C0.1 therefore proves transformation and value continuity, not durable storage or target executability.
