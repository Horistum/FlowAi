# Source observations

## Observed behavior
The source declares one output artifact and binds that exact artifact to the downstream input.

## Reconstructed intent
Represent artifact identity as a named Flow value relation plus an explicit adapter binding requirement.

## Invariants
- The consumer receives the exact artifact identity produced upstream.
- Ordering alone is insufficient continuity evidence.
- A renamed or missing artifact blocks the case.

## Ambiguities
Flow has no fifth artifact dependency kind; the case intentionally maps the artifact to VALUE and keeps target transfer as binding evidence.
