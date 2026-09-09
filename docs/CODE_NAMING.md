# Responsibility-based names

Kotlin files, declarations, conformance implementations and regression tests are named after the responsibility or invariant they implement. Do not encode delivery milestones or pull-request identifiers in names. For example, use `WorkflowFailureProjectionEvidence.kt`, `ExplicitMergeMaterializationRegressionTests.kt` and `CompilerAxisConformanceChecks.kt`.

Use descriptive names for work-package, report, migration-guide, check-inventory and authored corpus-assessment files as well. Their `version`, milestone, finding and check identifiers remain in the document contents. Repository links and live source inventories must point to the renamed files.

Actual public contract or release versions are different from delivery milestones: a versioned schema, immutable versioned baseline or version-specific migration guide may retain its version when the version distinguishes its contract. Do not erase meaningful technical identifiers such as SHA-256.

A recovery-specific validator should name its recovered domain and lifecycle responsibility, such as `WorkflowSemanticsRecoveryLifecycle`. A descriptive rename does not turn a bounded historical validator into a generic lifecycle framework.

A naming refactor must update declarations, imports, callers, evidence paths, documentation links and source inventories together. Preserve the required ordering of every source inventory. Do not introduce forwarding classes or type aliases just to retain milestone-based names.

Stable conformance IDs, workflow names inside existing fixtures, historical commit/run/job receipts and the contents of frozen external evidence are not implementation names. Preserve those identities and evidence bytes. Paths may move only with their consumers; changing an identity or the semantics of a fixture is a separate change.

`CodeNamingTests` checks repository filenames and structurally parsed Kotlin declarations, including tests and type aliases. The rule has positive and negative cases, ignores generated build directories, and permits real contract versions. Full compilation, all regression tests and standalone conformance must pass after renaming. Compare the complete test inventory across the rename rather than relying only on equal test counts.

## Product name versus technical identifiers

Horistum is the product name, as defined in [Product identity and retained technical names](PRODUCT_IDENTITY.md). This does not require replacing existing `FlowAi`, `Flow`, `flow` or `flowlang` identifiers. Keep `org.flowlang`, `flow-*` modules, existing functions and classes, CLI commands, file extensions and public contract identities unless a separate technical requirement justifies a compatible change.

New implementation names still describe responsibility rather than advertising the product. Do not add a `Horistum` prefix to every new type, introduce parallel namespaces, or create duplicate wrappers merely to restate the brand. Existing technical names are not deprecated solely because the public product name changed.

Branding is not permission to rewrite frozen evidence, schema identities, artifact metadata, source pins or architecture guards. A future functional change follows the existing compatibility and validation requirements; there is no mandatory follow-up mass rename.

## Documentation-reference-only source re-pin

The topology source manifest contains three references to the renamed adapter portfolio guide. These path edits change its raw SHA-256 even though its version, topology claims, support statuses, mechanisms and limitations remain identical. The profile source pin therefore records the relocated source digest `d9686e646881595efc173b1cf491bb863751da61dae34263252855e46b89954f` instead of the historical `1d687e5e441bb25087b795c5985da0f9ee7cae85571cda2677808758028cf6e8`.

`TopologyEvidenceReferenceMigrationTests` proves that reversing exactly those three documentation references reconstructs the entire historical pinned byte stream. It also verifies that the production profile authority still rejects even an additional unreviewed comment after the re-pin. The raw digest validation is unchanged; this is not a general normalization exemption or a topology support promotion. Other source pins remain unchanged.
