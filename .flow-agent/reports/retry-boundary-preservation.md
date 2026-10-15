# AR-06Y: Job-per-task Retry Boundary Preservation

## Problem and bounded outcome

The shared job-per-task lowering used by GitHub Actions and Tekton recursively
emitted retry-body tasks as independent jobs. It discarded the retry identity,
attempt limit, delay and backoff; an empty retry disappeared entirely. This left
diagnostic manifests unable to describe the authorized retry and concealed the
missing structural implementation behind otherwise native leaves.

Retry now uses the existing structural lowering in one diagnostic job, just like
the other unsupported grouped constructs. The manifest retains complete policy,
body order, nested and empty groups, enclosing job conditions and original leaf
dependency identities. Identity validation includes the retained group and every
nested step before a provider emits a manifest. Readiness exposes the unresolved
retry itself; grouping does not invent scheduling or continuity evidence.

## Ownership and limits

The production correction changes only shared adapter-contract lowering and its
identity inventory. Jenkins continues using its existing node-preserving path.
The semantic kernel, canonical graph, native catalogs, registry support, public
syntax, Target Manifest 3.0 and package versions are unchanged. Regenerate old
flattened diagnostic manifests from source rather than guessing retry policies.

This slice does not implement GitHub Actions or Tekton retry execution. Native
certification on another adapter must separately prove attempts, early success,
exhaustion and failure propagation with falsifying mutants. AR-06 remains active,
only A/B are formally accepted, AR-07 is planned, EF-09 is paused and F-20 remains
AR-07-owned. No new support or portable-execution claim is made.

## Validation boundary

Local Python tooling passed all 162 tests; Flow Agent validation, context
generation and whitespace checks passed before the candidate state update.
The local offline Gradle attempt stopped before compilation because the pinned
Kotlin 2.4.10 plugin is unavailable in the cache. No toolchain substitution was
made. Fresh exact-head and synthetic-merge compilation, complete tests, standalone
conformance, isolation proofs and all runtime producers plus portfolio are
required on GitHub. Final receipts belong in the PR description; this committed
report does not preclaim future CI success.

The initial PR CI run (38498136043) rejected the dependency regression fixture
before projection: `TaskNode.copy` changed `dependsOn` while retaining an empty
`dependencies` compatibility alias. The fixture now updates both fields. The
same regression also removes each nested leaf's alias in turn and requires the
existing `planning.dependency.projection.invalid` diagnostic on both job-based
providers. Production validation remains unchanged; final checks must run again
on the corrected head and merge candidate.

Six projection regressions exercise source compilation on all reference providers,
policy and body preservation, nested and empty retries, enclosing conditions,
leaf dependencies, group/step identity collisions, missing-implementation
readiness and the existing bounded Jenkins native output. Three lifecycle tests
retain the predecessor receipt chain and reject changed evidence, invented
validation, acceptance or finding closure. The existing 30-assessment,
104-observation runtime portfolio is revalidated without expanding its scope.
