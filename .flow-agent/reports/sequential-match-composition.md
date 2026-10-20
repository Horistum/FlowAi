# AR-06AE-C11 — Exclusive selection in sequential composition

Based on merged PR #238, `8a4ae19e1c9b34e353dd877dd63adaa8c9345774`.

## Architectural correction

Retry and loop already shared a recursive applicability contract, while match
maintained a separate task/condition/match allowlist. Each mapping existed, but
combining them was rejected solely by the separate control inventories. Match now
joins the same adapter-owned recursive plan/manifest validation. Its duplicated
body validator is removed. Selection predicates, complete retry policy, finite
collections and lexical item bindings remain independently checked at every depth.
Task count, result names and case position do not define applicability.

Cases and default branches inherit enclosing bindings without exporting local
values. Capturing an outer loop item through a match or retry is rejected, including
in unselected branches. Native leaf eligibility and workspace continuity remain
separate materialization obligations. Direct renderer calls recheck structural
payloads, exact wrapper parameters, branch inventory and recursive bodies.

The existing canonical graph and native rendering structures are preserved. No
Core, public schema, target registry or signed certification assessment changes.

## Independent evidence

The existing match producer retains all previous cases and adds both retry nesting
directions with finite iteration, default selection, empty selection, Boolean
inputs and permanent native checkout failures. Its source-derived oracle checks
canonical and manifest membership/ownership before comparing native call traces.
It distinguishes retry of the enclosing body from retry of only the selected body,
checks loop order and duplicates, and rejects default fallthrough, altered attempts,
repeated success and swallowed failure. The observer is unchanged and receives no
expected trace. The existing coverage consumer regenerates compiler inputs and
replays observations before publishing these cases under the `MATCH` row.

The planned inventory is 46 native executions: 18 baselines and 28 mutants, including
the original 12 executions. Kotlin regressions cover nested structures, lexical
capture, local values, independent leaf/continuity gates and forged manifests.
Python regressions include transient fault-budget probes, which are not claimed
as native transient-failure observations.

## Validation boundary

All 245 Python tooling tests, Flow Agent structure validation and whitespace checks
passed locally.
Exact-head and synthetic merge-candidate compilation, tests, 274 standalone
conformance checks, module isolation and every native certification gate remain
required. Current-revision CI is required; no predecessor run validates this patch.
The local environment lacks the pinned JDK 25/Gradle toolchain.

Dynamic match sources/guards, error branches, nonzero retry delay, dynamic
collections, parallel/approval/recovery interactions and general value/state
transfer remain open. This change does not establish transactionality, rollback,
fresh-workspace isolation, portable execution or target-wide certification.
AR-06 remains active, AR-07 planned, EF-09 paused and F-20 owned by AR-07.
