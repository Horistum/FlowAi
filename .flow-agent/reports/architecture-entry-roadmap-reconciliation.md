# Architecture entry and active-roadmap reconciliation — A-05 documentation

## Verified baseline

PR #248 merged at `6f8d6cefecc999be519cafc9455cdf4dbe63c5c0`. Its tree `0a6b83e2f74c6a6885da8e75c9e48532d959d630` matches validated head `27cfb2e3251e5b10622f7eed6f053766b00a436a`. [Flow CI 39965942968](https://github.com/Horistum/FlowAi/actions/runs/39965942968) and [runtime CI 39965943091](https://github.com/Horistum/FlowAi/actions/runs/39965943091) passed for that head; PR #248 records 2,362 JVM tests, 291 standalone conformance checks and 257 Python tests per required revision. These are predecessor receipts, not validation of this documentation candidate.

Recovery revision 62 selected A-05 after A-06. The maintainer authorized continuation, and the work package was committed before edits. The 2026-10-20 audit remains unchanged.

## Findings and patch

The old architecture entry page described an Intent draft, module-owned meaning and broad platform capabilities. Current production code instead has three frontends entering one compiler, a typed canonical graph with derived plan views, explicit provider composition and bounded evidence gates. The README repeated the older linear pipeline and target optimism.

The replacement page links actual production owners and all fourteen Gradle modules. Its dependency table was compared with each module's production project dependencies. It distinguishes source layout from compilation ownership, including the explicit frontend dependency of generic materialization. It describes present AI review/provenance while retaining heuristic confidence and facade retirement as AR-07 work. Current input, handler, data-operation, global-variable and availability admission limits are linked without claiming implementation of rejected features.

The existing root roadmap is a completed historical handoff. Its pointer leads to post-toolchain selection, whose current decision points to the recovery roadmap. The Flow Agent README now documents that one route; the root README, agent contract and runbook link it. Root YAML comments explain the handoff without changing parsed data or selectors. The existing context generator still reports its completed root-stream lifecycle; documentation explicitly directs readers to the recovery correction separately. No new selector or second state authority is added.

The module-extraction document receives an AR-03D historical banner; its original counts, APIs and lifecycle receipt remain intact. The root README uses the retained technical repository name without pinning it to a former owner.

## Validation and scope

The baseline and final Python suites each passed all **257 tests**, with Flow Agent validation and context generation. Final documentation checks resolved **66 relative Markdown links/anchors**, verified all **14 module dependency rows** and confirmed that root-roadmap parsed data is unchanged. The eleven-file scope matches the work package; the roadmap route and release state agree, historical milestones and unrelated release-state fields remain unchanged, and diff checks pass.

No production source, tests, build inputs, public schemas or historical audit observations are changed. No prose-mirroring tests or conformance checks are added. The existing exact-head, synthetic merge-candidate, isolation and runtime CI gates remain required. No local JVM run is claimed for this documentation-only patch; final CI results and immutable head/tree bindings belong in the PR validation record.

Recovery revision 63 and release state 122 reconcile merged A-06 admission and record this bounded A-05 documentation candidate after local validation. A-05 remains open for AR-07-owned migration/AI cleanup. A-02/A-03 full semantics and general availability-state compaction remain open behind their existing boundaries. AR-06 remains active, AR-07 planned and EF-09 paused.

After this correction, select the next bounded AR-06 composition gap from current coverage; independent GitHub workspace composition admission is still open. No adapter support, signed portfolio scope, portable-execution claim, contract version or runtime behavior is promoted by this patch.
