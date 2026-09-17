# Composed module and system-contract identity

A `ModuleCatalog` is a declaration source. At compiler, validator, planner or target
composition, `ModuleCatalogIndex.capture` validates and snapshots `allModules()` once.
Custom provider lookup overrides cannot override those declarations. A malformed
catalog is configuration failure and fails before source compilation or target generation.

## Identity contract

Module names are globally unique within a composition, as are system type names.
Names are case-sensitive. The module-map key equals `FlowModule.name`; each system-map
key equals `SystemTypeContract.name`. Module names and versions and system type names
must be nonblank. Two modules cannot declare the same system type, even with equal
schemas. Two versions of the same module require a future explicit selection contract;
registration order is not a version-selection policy.

The public lookup returns the owning `FlowModule` and the exact `SystemTypeContract`.
For a module `remote-provider` version `2.3` declaring system type `remote`, an Intent
system of type `remote` imports `remote-provider` version `2.3`, not module `remote`
version `1.0`. Existing source-language names are not renamed or sanitized. An unknown
system type returns no contract, retains existing explicit validation diagnostics and
never fabricates an import or borrows another module's type.

## Composition and mutation

The index copies and protects module/system/action identity maps and system input schema
maps. Mutating the provider's list or maps later cannot change the captured identity or
substitute a contract. Returned identity collections are unmodifiable. This bounded
snapshot is not deep immutability of arbitrary nested schema defaults or action payloads.
A new catalog requires a new composition; hot swapping through an existing compiler is
not supported by this contract. Concurrent mutation during the construction of an input
collection is not supported; callers must provide a stable collection for capture.

Descriptor directory/text loaders and direct registry constructors use the same index.
Direct custom catalog consumers should capture before repeated lookup; all production
composition roots do so. An empty catalog stays empty and acquires no built-in defaults.

## Diagnostics and verification

`DUPLICATE_MODULE_ID`, `DUPLICATE_SYSTEM_TYPE`, `MODULE_CATALOG_KEY_MISMATCH`,
`SYSTEM_TYPE_KEY_MISMATCH` and `MODULE_CATALOG_INVALID_IDENTITY` identify invalid
composition. The canonical descriptor loader preserves these codes in its existing
`ContractException` boundary. They are configuration diagnostics, not source syntax errors.

Verification includes positive unique catalogs, ambiguity in both orders, matching-schema
conflicts, malformed identity keys, dormant conflicts, permutation invariance, provider
lookup override rejection, source/output mutation tests, real compiler schema rejection
and owner-preserving Intent lowering. Independent conformance checks exercise rejection,
owner/version preservation and snapshot stability. None certifies a new target runtime.
