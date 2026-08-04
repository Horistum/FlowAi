# Source observations

## Observed behavior
The pinned HashiCorp setup-terraform workflow initializes a Terraform configuration, applies it with automatic approval and reads the resulting Terraform output.

## Reconstructed intent
Preserve initialization as preparation, `terraform apply` as an explicit infrastructure `PROVISION` operation and the resulting state as a named value consumed by verification.

## Invariants
- The apply step remains infrastructure state-changing intent rather than a generic command.
- Initialized configuration reaches apply through an explicit value relation.
- Provisioned state reaches verification through a separate value relation.

## Ambiguities
The upstream workflow applies a local test configuration. C0.1 proves infrastructure state-change semantics and result continuity, not production target readiness or a specific cloud provider.
