# Reviewed discovery interpretation and supporting inventory provenance

Baseline: merged PR #269 at `bb4a26d58009292d147b23451656d42e8fb03994`.
Work package: [AI-A3-S3.2](../work-packages/managed-inventory-provenance.yaml).
Contract and migration: [Managed inventory provenance](../../docs/MANAGED_INVENTORY_PROVENANCE.md).

## Product scope correction

PR #270 originally prioritized signed exports while the caller still supplied the
source-to-operation mapping. That was insufficient progress toward the requested
ability to solve unfamiliar complex tasks. [Discovery v3](../../docs/REVIEWED_DISCOVERY.md)
now lets the external agent propose the interpretation and complete structured plan.
A separate caller review confirms the exact candidate before unchanged independent
compiler constraints and additive observed-value checks are applied. The agent cannot
supply its own approval. V2 remains compatible; provenance is optional supporting work.

Sixteen manually authored transcript cases exercise infrastructure readiness and
isolated database recovery with two source schemas each. They reject wrong values,
flattened handlers and an old plan under a changed inspection-only goal. Unit tests
cover approval identity, interruption, evidence changes and unknowns. These are protocol
and installed-compiler tests, not proof that a live model understands the goal. Live
unfamiliar-system and runtime-outcome acceptance remain open. Final CI receipts belong
in the PR metadata and must correspond to the published revision.

The first installed run exposed a compiler contract mismatch: legacy literal/value
checks intentionally refuse structured operation regions. Requirements v5 now adds
an explicit selected-operation scope for parameter values and resolved producer
edges. Original activation/count/path/failure obligations remain independent. Eight
JVM regressions cover legacy refusal, version freezing, contradictory/absent nodes,
producer ambiguity, failure placement and schema/reader agreement.

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

S3-live-task-driven-discovery-evaluation is the next slice. AI-A3, S3, AR-06/AR-07 and the
existing live/held-out gates remain open; EF-09 stays paused.
