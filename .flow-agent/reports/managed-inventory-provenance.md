# Managed inventory provenance and collection age

Baseline: merged PR #269 at `bb4a26d58009292d147b23451656d42e8fb03994`.
Work package: [AI-A3-S3.2](../work-packages/managed-inventory-provenance.yaml).
Contract and migration: [Managed inventory provenance](../../docs/MANAGED_INVENTORY_PROVENANCE.md).

## Change and ownership

An unchanged local export could previously supply accepted bindings regardless of
its issuer or age. An optional managed-root policy now requires an independently
trusted Ed25519 collector grant, matching system/scope and original-request-bound
capture challenge. Collection records an actual bounded process response and interval;
the provider neither chooses that process nor receives its signing key.

The host authenticates exact exported files and directory structure, not just the
files eventually selected by a model. It checks collection age at every existing
source-validation boundary. Expiry during clarification or compilation invalidates
dependent observations, facts and findings and prevents acceptance. A fresh signed
export cannot replace the old one inside a session.

Raw attestations remain in the retained ledger for independent review; provider
context contains bounded provenance receipts without the signed answer-file inventory.
The final binding receipt connects the compiler-assessed graph and observed parameter
values with a post-compilation inventory assessment. Existing canonical compiler
requirements remain the only parameter-fidelity evaluator.

## Evidence scope

Regression tests cover changed signatures, unrelated valid keys, signature domains,
scope/request substitution, expiry, future/reversed intervals, clock rollback,
undeclared or modified files, links, traversal, output failure, credential redaction,
private key handling, clarification and the actual prepare/collect CLI.

The installed proof uses a separate read-only SQLite collector process, independently
populated resource arrays, keyed datasets and nested policy documents. Twelve cases
check acceptance, rejected cross-scope input, changed export bytes and expiry after a
successful real compilation. Original requests contain no answer addresses. Previous
authoring, discovery and runtime proofs remain enabled. Final PR metadata must report
validation for the exact published head and merge candidate.

## Open boundaries

A signature authenticates the configured collector's assertion, not upstream truth.
Within the accepted collection-age window, a remote system may already have changed.
Cached provider responses, source completeness, production key custody/revocation and
the collector's read-only credentials are not certified by this mechanism. The process
fixture is deterministic, with no live-model generalization claim.

S3-reviewed-live-discovery-evaluation is the next slice. AI-A3, S3, AR-06/AR-07 and the
existing live/held-out gates remain open; EF-09 stays paused.
