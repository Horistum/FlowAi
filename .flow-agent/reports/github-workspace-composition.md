# AR-06AE-C12: Composable GitHub workspace transfers

Frozen adapter profile evidence remains byte-identical; current applicability is supplied by the existing scoped-declaration overlay.

The predecessor PR #239 merged at `06e7428d59721ef2670338ed4308892d1f1f6849`. AR-06 remains active.

## Problem and change

GitHub workspace support depended on the complete two-action sequence rather than the resolved relation. Unrelated tasks and repeated consumers therefore lost support. The adapter now admits unconditional top-level `git.checkout/source` → `docker.build/source` endpoints with explicit ordering and one workspace requirement per consumer. Upload deduplication and per-consumer downloads preserve fan-out; independent producer paths keep different artifact identities.

Independent native falsification preparation exposed another defect: the image action used default Git context, bypassing downloaded bytes. Rendering now selects the local workspace path and preserves workspace-relative Dockerfile paths. Exporting checkouts disable credential persistence before uploading hidden files. The current GitHub executable reference and its active scope identity change accordingly; the frozen Jenkins reference remains unchanged. The promotion still names only checkout-build-image with the same support class. An obsolete task-sequence identity is rejected instead of being silently aliased to the new contract.

Direct rendering validates transfer inventories, placement, endpoint identities, artifact pairs and ordering. Unsupported channels, unresolved sources, nested endpoints and multiple-source restores remain blocked. Core, public artifact schemas, VALUE/STATE semantics and target-wide profiles do not change.

## Evidence

JVM regressions cover varying consumer counts, independent inputs, independent producer paths, unrelated conditions, ambiguity, missing ordering, unsupported endpoints and tampered manifests. The native workflow binds every generated action to its executed envelope, then runs real upload/download and Docker build actions for two consumers and an independent checkout. Source-derived graph/manifest checks and file SHA-256/size observations are independent of the adapter's matching code.

Missing-transfer and hidden-file mutants must fail their builds; the workflow explicitly checks those outcomes. Skips, missing mutants and changed bytes fail verification. Evidence is bound to source/workflow revisions and CI run/attempt and retains file receipts. This CI-bound workflow proof is not yet an independent composition-coverage admission or signed certification bundle. Multiple independent producer paths remain JVM-tested applicability rather than an expanded native claim.

## Validation state

Local Flow Agent validation and all 253 Python tooling tests pass. The pinned JDK 25 toolchain is not available locally; compilation, JVM tests, standalone conformance, module isolation, native runtime checks and the synthetic merge candidate require the current GitHub CI revision. Final run identities and results belong in the PR description after those jobs complete.

AR-06 stays open. Remaining gaps include conditional workspace endpoints, metadata-preserving transfer, VALUE and STATE transport, and evidence admission for this new native producer. No target-wide support or portability promotion is claimed.
