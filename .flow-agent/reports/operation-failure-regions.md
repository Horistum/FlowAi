# Explicit failure regions and bounded observation retries

Baseline: PR #262 head `7be072e6ae1e5190674a7bf9bd9f8f9ceea7d3d6`.
PR #262 was still open when this follow-up was started; no merge is implied.
Work package: [AI-A3-S2.3](../work-packages/operation-failure-regions.yaml).
Contract and migration: [Failure regions](../../docs/FAILURE_REGIONS.md).

## Result

Intent 3.1 adds explicit protected and handler bodies and an explicit propagate or
recover disposition. Existing canonical try and retry nodes remain the execution
meaning. Fixed, bounded retries admit known read-only semantic effects; changing or
unknown effects and nested retry multiplication are rejected.

Requirements 4 selects semantic operations independently of candidate IDs and checks
the exact ordered region membership, failure disposition and retry bounds. Original
error bindings cannot be overwritten. Partial body values remain unavailable in
failure handlers, and successful-only values cannot escape a recovered alternative.
Lowering receipts bind the actual complete protected, handler and retry structure.
Reviewed proposal snapshots retain and recheck the new nested bodies.

Intent 2.0/3.0 and requirements 1–3 retain their contracts. The authoring host captures
the additional public schema under prompt version 4 without exposing reviewer-owned
requirements to the provider. Its clarification path cannot weaken failure obligations.
The existing effect and meaning authorities are reused; their dependency inventory
records the new source validation caller instead of introducing another effect model.

## Evidence and limitations

Infrastructure readiness, isolated database recovery and credential maintenance use
the same production path. Fixtures and expectations are manually authored. They do
not establish live-model generalization, provider support or external runtime recovery.
New integration cases cover changed dispositions and retry bounds, handlers moved onto
normal paths, changing retries, unavailable partial results and blocked target rendering.

Independent region checks currently admit root sequential attempts and direct read-only
retry bodies. Conditional activation, nested attempts, region-local aliases/merges and
workflow failure handlers require review. General structured authoring can preserve
broader shapes without falsely certifying their independent fidelity.

Retries respond to operation failure, not a successful false result. No idempotence,
automatic cleanup, newly created handle discovery or compensating action is inferred.
Outcome observations version 1 remain attempt-unaware and do not certify reached failure
regions. Actual recovery, changing retries and safe continuation need subsequent evidence.
AI-A3, AR-06/AR-07, F-16/F-20 remain open and EF-09 remains paused.

## Validation

- 17 new JVM tests and the 21 existing structured-authoring tests pass.
- 340 Python tooling tests pass, including immutable clarification obligations.
- All 15 new installed CLI variants pass across the three domains.
- Full JVM regression, final installed conformance and final-head/merge-candidate CI
  remain required. The PR records the authoritative final revision receipts.

Local validation uses the same pinned Gradle/Kotlin dependencies and JDK 25 with the
temporary offline Maven mirror. No dependency repository override is included.
