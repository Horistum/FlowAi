# AR-06R: Match Branch Preservation

## Problem and bounded outcome

The shared target lowering discarded each match case condition and concatenated
case, error and default bodies. This made the diagnostic adapter boundary unable
to describe the authorized match, and blocked honest native certification.

The projection now retains the source, ordered case conditions, body membership
and explicit error/default wrappers. Empty cases and empty fallback bodies survive
lowering. Derived wrapper identities are checked before either node-preserving
or job-per-task providers emit a manifest. The manifest contract rejects malformed
branch shapes and execution bindings on grouping wrappers. Readiness and review
inventories exclude empty wrappers from leaf-operation counts.

## Ownership and limits

Only target-neutral adapter projection and conformance lifecycle code change.
The semantic kernel, canonical graph, concrete native catalogs, registry support,
public syntax and versions remain unchanged. The existing Target Manifest 3.0
structure carries the corrected diagnostic representation; migration guidance
requires regenerating historical flattened match manifests from source.

AR-06 remains active; only A/B are formally accepted. AR-07 is planned, EF-09 is
paused and F-20 retains AR-07 ownership. This slice prepares the native match
boundary and does not certify match execution or promote support or portability.

## Validation boundary

Local Python tooling (162 tests), Flow Agent validation, context generation and
whitespace checks passed before the candidate state update. The pinned Kotlin
2.4.10 plugin cannot resolve locally; no toolchain substitution was made.
Exact-head and synthetic-merge compilation, full tests, standalone conformance,
isolation proofs and all existing runtime producers plus portfolio are required
on GitHub for this candidate. Their final receipts belong in the PR description;
this committed report does not preclaim future CI success.

New projection tests cover the actual source-to-manifest path on Jenkins, GitHub
Actions and Tekton, nested and empty matches, source/condition retention,
flattening, omissions, duplicates, reordering, misplaced wrappers, forged
execution bindings and identity collisions. Lifecycle tests preserve the complete
predecessor receipt chain and reject invented acceptance or validation.
