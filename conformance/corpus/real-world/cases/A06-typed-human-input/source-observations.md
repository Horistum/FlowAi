# Source observations

## Observed behavior
The source blocks execution, collects structured release fields, and only then invokes the release command.

## Reconstructed intent
Model release fields as typed inputs and model approval as a separate producer whose output gates release.

## Invariants
- Release type remains a constrained beta/stable choice.
- Approval is distinct from typed release metadata.
- The release step consumes an explicit approval output.

## Ambiguities
The source does not specify approver identity or production environment policy, so the case does not claim production authorization semantics.
