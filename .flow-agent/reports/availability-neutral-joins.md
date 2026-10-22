# AR-A04B: Availability-neutral alternative joins

Predecessor PR #244 merged at `99fdaddb70aba4b0c42cdf821edd0687717a06a0`.
This is the authorized A-04 state-reduction follow-up to deterministic resource
containment, within the system audit's compiler correction sequence.

## Change and safety argument

Empty and availability-neutral exclusive alternatives previously multiplied path
identities even though they introduced no distinction relevant to value reads.
The analyzer now retains each alternative's entry and analyzed exit. After local
loop/error bindings are restored, an exact comparison can reuse the original
pre-split state if every alternative is unchanged.

This applies only to complete if, loop zero/body, match and try/error alternatives.
Exact state equality includes reachability, every binding, producer/external path
coverage, value type and explicit-merge status. Guard-refined subsets are compared
against their own entries; reusing the original input preserves their complete
union and its partial/definite distinction. Every alternative is analyzed first,
so diagnostics, statement snapshots and authored control nodes are retained.
Concurrent joins and short-circuit subset joins retain their existing algorithm.
Changed exits retain the existing explicit join. Equivalence scans are charged to
the shared budget; no limit or frontend bypass is added.

The production change is confined to the shared analyzer and join implementation.
It adds no runtime execution, target dependency, schema, alternate semantic model,
public compiler entry point or adapter claim. Reduced raw path evidence can change a new
compilation's graph digest; authorization is recalculated and integrity checked on
the new graph.

## Evidence

- The byte-identical 557-byte audit source (SHA-256
  `41fc2b8eaa263269e7a23c0b9b5b571bb3d42d2b702a624a461fdc1225385de6`)
  completes with one path in a child JVM capped at 256 MiB.
- Source sequences of 4, 8, 12, 20, 100 and 500 neutral conditions retain one path.
  The merged predecessor tests recorded 16/256/4,096 paths for 4/8/12 conditions
  and rejected the original 20-condition reproducer at its resource boundary.
- Repeated and nested if/loop/match/try alternatives preserve exact input states,
  including restored item/error bindings. Tests retain accepted guarded reads,
  rejected partial and ambiguous reads, and exact explicit-merge incoming coverage.
- Terminating alternatives cannot be erased; terminating an absent-value guard
  leaves the defined producer reachable. Concurrent paths remain non-mergeable.
- Diagnostics in neutral branches and approval nodes in both plan arms survive.
- Budget rejection fixtures now create distinct producer states. Every expanding
  construct still exhausts a deterministic budget, and direct analyzer, validator,
  planner and common compiler reject without an accepted partial result.
- Standalone conformance adds neutral-source admission and retains source/direct
  planner rejection. Its positive merge and negative partial read now pass through
  twenty neutral conditions before the consumer.

## Validation

Full local Gradle test/installDist run: 2,318 tests across 402 suites passed, with
zero failures, errors or skipped tests. This includes all ten new neutral-join
regressions and the 256 MiB reproducer. Python tooling: 257 tests passed. Flow Agent
validation passed. Standalone installed-distribution conformance passed all 281
checks.
Final exact-head and synthetic merge-candidate CI evidence is recorded in the PR.

## Roadmap disposition

A-04 resource containment is merged; this candidate reduces availability-neutral
splits. General compaction of distinct states remains open and bounded by the
existing limits. This is not a claim of unrestricted symbolic scalability.

The next audit correction is A-02: preserve typed data-operation payloads in the
canonical plan or explicitly reject unsupported forms. A-03, A-05 and A-06 remain
open. AR-06 remains active, AR-07 planned, EF-09 paused and F-20 AR-07-owned. The
original audit evidence and historical candidate records remain unchanged. No
signed portfolio or target support is promoted.
