# Atomic artifact publication (AR-05D)

CLI `--out`, `standard-draft` and `standard-export` now publish through the same
product-owned `AtomicArtifactWriter`. Choose a **new or empty output directory**
for every invocation. An existing nonempty directory is rejected unchanged; use
separate versioned output paths for successive bundles. Symbolic output paths,
traversal components and non-regular artifact entries are rejected.

The writer bounds the initial encoded batch, creates private sibling staging,
writes each file with `CREATE_NEW`, forces file data, re-reads actual bytes and
checks size, SHA-256, strict JSON, declared schema and observed standard version.
All files are re-read again before publication. The integrity manifest is written
last. A single `ATOMIC_MOVE` publishes the directory on the same filesystem.
Unsupported atomic moves fail; there is no non-atomic fallback or backup rename.
Handled failures attempt to remove staging and leave the destination absent or unchanged.

Generated JSON with a declared schema must pass the installed schema. The shared
validator supports Flow's explicit JSON Schema subset and rejects unsupported
assertion keywords. This is syntax validation; compiler/domain authorities still
own semantic validity. Output schemas always come from the installed distribution;
external contract inputs cannot relax this output contract. JSON without a declared schema is labeled `JSON`, never
`JSON_SCHEMA`. Release reference documents and deliberately invalid conformance
fixtures are labeled `BYTES` and receive hash/size validation, not output-schema
validation. The installed schema resources are included in resource provenance.
Explicit `--contracts` overrides remain complete snapshots: update older override
roots with the newly inventoried schema files before using this distribution.
When schema files are also shipped in a bundle, their actual hashes must match the
schemas used to validate the outputs, including the final integrity manifest.

`artifact-integrity-report.json` version `1.1` contains `publication.coveredFiles`
with relative paths, actual lengths and hashes, validation kinds, schema hashes
and observed versions. Its explicit `excludedPaths` contains only itself, avoiding
a circular self-hash. Required-file observations exclude that same final manifest.
The writer returns a separate `PublishedArtifactReceipt` binding its actual bytes
and absolute published directory after the atomic move. Receipts are integrity
evidence, not signatures or authentication; consumers can pin the returned
manifest receipt through `ArtifactPublicationVerifier.verify`.

The schema check also corrects the former `execution-plan.json` export of the
internal planner shape (`Task`, etc.). That filename now carries the existing
canonical public ExecutionPlan 2.4 model (`task`, etc.), matching
`canonical-execution-plan.json` and its declared schema. Internal planner types and
target rendering semantics are unchanged.
Release metadata also preserves the schema-required `nextCoreItem: null` when no
next Core item exists; omitting that key is not a valid substitute for null.

The public `standard-verify` command requires an actual-byte receipt. Missing,
changed, added or deleted files fail verification; failed verification does not
persist a report. Historical structural fixtures explicitly select
`requirePublicationReceipt = false`. Release export uses that structural layer
inside staging before the final manifest exists. Compliance consumes the verified
base artifact layer, then the final manifest covers compliance, draft and the
structural verification report too. No persisted integrity report is made from
expected names before writes. In-memory `ArtifactIntegrityAnalyzer` remains a
logical contract-analysis API and emits no publication evidence.

Structural verification reports carry `verificationVersion: 1.1-structure`;
public verification uses `1.1` and includes the actual-byte publication check.
`standard-draft` previews without `--out` use a temporary validated publication
and remove it before returning a non-persisted draft presentation.

Product limits remain 8 MiB/file, 32 MiB/batch and 256 files, including the final
manifest. Release exports allow 1024 files for their documentation/reference tree,
with the same byte limits. Directory traversal is also bounded. A full batch with
no room for the manifest is rejected before publication.

Atomic visibility is not an unconditional power-loss durability guarantee. Files
are forced before validation. Directory fsync is attempted where supported, and
its outcomes are reported; a parent fsync failure after the commit point does not
pretend the already published directory was rolled back. The destination parent
must be caller-controlled. Concurrent cooperative writers cannot replace a
nonempty published directory; protection against a hostile user replacing ancestor
paths during the operation is outside this API. Process termination can leave a
private staging directory for operator cleanup, but cannot publish a partial bundle.

AR-05E retains integrated independent closure. This slice does not close findings,
activate AR-06 or promote adapter support.
