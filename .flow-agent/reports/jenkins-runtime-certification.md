# AR-06D candidate: real Jenkins checkout behavior

AR-06A through AR-06C establish integrity, compiler binding and runner-origin
checks. Their synthetic observations do not prove execution. This candidate adds
the first adapter-owned real-runtime fixture: native checkout, with independent
workspace observations and omission/branch-substitution mutants.

The conformance runner captures the original artifact through the existing
compiler/rendering authorities. A disposable Jenkins controller executes it
unchanged using actual Declarative Pipeline and Git plugins. The observer checks
the configured artifact digest and workspace file bytes. A host-owned key signs
collected records and the existing authenticated admission performs all input,
coverage, byte, runtime and mutant checks. No executor enters Core or the product
CLI; a dedicated Gradle verification task and CI workflow own the external test.

The behavior matrix, input intent and runtime setup belong to the Jenkins adapter.
Jenkins runs without networking or published ports. Its only Git server is local
to the disposable container. Timeout and infrastructure failures are failures of
the proof, never successful negative evidence. The immutable built image ID binds
runtime prerequisites; raw plugin/version records and logs remain inspectable.

## Dependency state

The maintainer requested the next step after AR-06C. At preparation, both GitHub
and direct Git ref inspection still showed PR #200 open, with main at
`4bea533762e3cff83192c7f9d8ba8244dd3c2598`. This patch therefore stacks on its head
`febe35775a333d15f71ef39068527cb116a90427`. It does not invent a merge or actual-main
receipt. The selected accepted lifecycle remains unchanged; AR-06D is a candidate
until predecessor merge and main validation are independently verified.

## Validation and limits

Unit tests cover source/artifact binding, complete runtime records, workspace
differences, failed mutant runs, script substitution, duplicate/missing runs,
version drift, bounded strict JSON and exact mutation anchors. Full root tests,
standalone conformance and physical source-deletion proofs remain required. The
new `jenkins-checkout-runtime` job must separately pass real execution; unit tests
alone never establish this claim. Current CI results are recorded in the PR.

This is one native-leaf scenario, not target-wide certification. Public support
views and versions do not change, all AR-06 findings remain open, and EF-09 remains
paused. GitHub Actions execution, control-flow scenarios, full matrix coverage
and evidence-derived public views remain subsequent work. Ephemeral CI signing
trust demonstrates the admission path; production runner provisioning and key
custody remain external responsibilities.
