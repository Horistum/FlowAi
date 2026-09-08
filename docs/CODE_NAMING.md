# Responsibility-based names

Kotlin files, declarations, conformance implementations and regression tests are named after the responsibility or invariant they implement. Do not encode delivery milestones or pull-request identifiers in names. For example, use `WorkflowFailureProjectionEvidence.kt`, `ExplicitMergeMaterializationRegressionTests.kt` and `CompilerAxisConformanceChecks.kt`.

Use descriptive names for work-package, report, migration-guide, check-inventory and authored corpus-assessment files as well. Their `version`, milestone, finding and check identifiers remain in the document contents. Repository links and live source inventories must point to the renamed files.

Actual public contract or release versions are different from delivery milestones: a versioned schema, immutable versioned baseline or version-specific migration guide may retain its version when the version distinguishes its contract. Do not erase meaningful technical identifiers such as SHA-256.

A recovery-specific validator should name its recovered domain and lifecycle responsibility, such as `WorkflowSemanticsRecoveryLifecycle`. A descriptive rename does not turn a bounded historical validator into a generic lifecycle framework.

A naming refactor must update declarations, imports, callers, evidence paths, documentation links and source inventories together. Preserve the required ordering of every source inventory. Do not introduce forwarding classes or type aliases just to retain milestone-based names.

Stable conformance IDs, workflow names inside existing fixtures, historical commit/run/job receipts and the contents of frozen external evidence are not implementation names. Preserve those identities and evidence bytes. Paths may move only with their consumers; changing an identity or the semantics of a fixture is a separate change.

`CodeNamingTests` checks repository filenames and structurally parsed Kotlin declarations, including tests and type aliases. The rule has positive and negative cases, ignores generated build directories, and permits real contract versions. Full compilation, all regression tests and standalone conformance must pass after renaming. Compare the complete test inventory across the rename rather than relying only on equal test counts.
