# Source observations

## Observed behavior
The pinned Velero reference creates a restore and validates that its referenced backup is usable before restoration proceeds.

## Reconstructed intent
Preserve the operation as target-neutral `RESTORE`, retain the authored recovery point and expose restored state as an explicit value consumed by verification.

## Invariants
- Restore is a semantic recovery operation, not a deployment alias or command string.
- The recovery point remains authored input to `RESTORE`.
- Restored state has an explicit producer and consumer.

## Ambiguities
The source has detailed Kubernetes-specific restore mechanics. C1.0 intentionally does not promote those mechanics into universal Flow semantics or infer support for a concrete target.
