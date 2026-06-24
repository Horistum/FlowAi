# Compatibility and Migration Policy

Introduced in v0.4.6.

Flow uses minor versions for additive public-standard changes. A minor release
may add optional fields, schemas, conformance vectors and stricter checks when
they make existing semantics explicit.

Breaking changes are not allowed in a minor release. Breaking changes include:

- removing a public field,
- renaming a public field,
- changing the meaning of an existing capability,
- changing the meaning of an existing diagnostic,
- weakening a safety invariant.

Deprecations require a minimum two-minor-release window. A migration note must
explain the old behavior, the new behavior, and the conformance vector that
prevents accidental regression.

The compatibility policy is exported as
`compatibility-migration-policy.json`.
