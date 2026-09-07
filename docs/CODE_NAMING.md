# Code naming and historical evidence

Production code, conformance implementations and regression tests are named after their responsibility or the invariant they exercise, not the milestone or pull request that introduced them. Use a matching Kotlin file and primary declaration name, for example `WorkflowFailureProjectionEvidence.kt` or `ExplicitMergeMaterializationRegressionTests.kt`.

Milestone IDs belong in work packages, roadmap entries, migration records, completion reports and commit/PR history. A recovery-specific implementation should identify the recovered domain and its lifecycle responsibility, such as `WorkflowSemanticsRecoveryLifecycle`; a descriptive rename must not imply that a milestone-specific validator has become a generic lifecycle framework.

Stable conformance IDs, historical receipt identities and existing fixture identities are not class names. Keep them unchanged in a naming-only refactor. Renaming an evidence identity requires its own compatibility decision; it must not silently invalidate historical CI or change a fixture's canonical meaning.

A code rename must update declarations, imports, callers, evidence paths and source inventories together. Do not retain empty forwarding classes or type aliases solely to preserve an unpublished milestone-based implementation name. Verify the full test and conformance inventories after the rename, rather than relying only on successful compilation.

Apply this convention to new code and the code being changed. Unrelated historical implementations are not automatically renamed as part of another milestone's delivery.
