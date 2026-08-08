# Source observations

## Observed behavior
The pinned Velero reference explicitly creates a backup for selected Kubernetes content and can wait for the backup operation to finish.

## Reconstructed intent
Preserve the operation as target-neutral `BACKUP`, expose the resulting recovery artifact as an explicit value and verify that artifact independently.

## Invariants
- Backup is a semantic data-protection operation, not command text.
- The recovery artifact has an explicit producer and consumer.
- Source tooling and Kubernetes vocabulary remain evidence only and do not enter Core capability identity.

## Ambiguities
The source describes one concrete backup product. C1.0 proves representability of the universal backup operation and continuity of its result, not Velero support or a Kubernetes execution contract.
