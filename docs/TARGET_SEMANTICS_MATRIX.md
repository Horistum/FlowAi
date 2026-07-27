# Target Semantics Matrix

Introduced in v0.4.8 and hardened by bounded correction v0.9.7.9.11.

The target semantics matrix publishes only claims that can be derived from the
active target registry or from provider-owned native projection contracts. It
is not a target-name lookup table and it does not describe hypothetical
platform mechanisms that the composed distribution cannot materialize.

## Evidence authorities

- Target ids are loaded from the target registry.
- Condition support is derived from `TargetExpressionSupport` over the reference
  expression corpus.
- Manual approval support is derived from provider-owned
  `TargetNativeApprovalProjectionDefinition` contracts.
- Secret and artifact support is derived from typed bindings in the composed
  `TargetNativeProjectionCatalog`.
- Absence of provider evidence is published as
  `adapter-required-review-only` or `not-declared-review-only`, never as a
  positive native capability.

The conformance check `v0.4.8.target-semantics-matrix` independently derives the
same values from the registry and provider catalogs. It also proves that an
unknown handwritten mechanism label makes the matrix status `FAIL`.

## Published feature families

The current matrix publishes only feature families with an implemented evidence
resolver:

- `conditions`,
- `approvals`,
- `manual-gates`,
- `strict-manual-approval`,
- `secrets`,
- `artifacts`.

Additional feature families may be published only after a provider-neutral
evidence authority and a negative drift test exist for them.

## Current approval evidence

The built-in Jenkins provider owns a native `approval.manual` payload contract.
The built-in GitHub Actions and Tekton providers do not. Their approval,
manual-gate and strict-manual-approval entries are therefore
`adapter-required-review-only`; they are not executable native claims.

Unsupported, partial or undeclared semantics must remain visible through
readiness diagnostics and may not be silently promoted by documentation or
export metadata.
