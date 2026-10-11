# AR-06Q candidate: sequential native Jenkins literal loops

The Jenkins adapter renders authorized finite string-literal lists as native
`for` loops. Its plan-specific resolver requires the composed provider, safe loop
variables and bodies containing native checkout tasks and existing conditions.
It preserves order, duplicate items and empty input. Dynamic collections, nested
loops, other body shapes and loop-output continuity remain outside this scope.
Core, public syntax and the generic registry are unchanged.

Three complete compiler-generated pipelines run on the existing offline disposable
Jenkins controller. The order scenario contains duplicate values and selects
checkout branches through conditions using the loop variable. Reversed and
deduplicated lists preserve the final result and marker but change native branch
order or multiplicity. The empty scenario must skip a failing body and run its
successor; mutants force an iteration or omit the successor. The failure scenario
must stop after its second iteration, preserving the native missing-revision
error origin and skipping both the later iteration and successor. Mutants omit
or catch that failure.

The observer reads ordered native checkout arguments and distinct step identities
without instrumenting the baseline pipeline. Independent fixed oracles include
result, marker, checkout count, ordered branches, errors and their positions,
and terminal failure identity. The existing owner signs outside the controller.
The portfolio requires seventeen assessments and fifty-three signed runs across
thirty-six rows and eight bounded construct/target pairs. No public support or
portable execution is promoted.

## Predecessor boundary

PR #213 merged as e24f81d3888bc78a89975280c593b1d4059b7864, tree
001f64c7c1d373c12a90bc10da5a8ff4e32f3817, identical to its validated head and
synthetic merge candidate. Flow CI 38073053296 passed 2,054 tests in 353 suites
and 274 ordered conformance checks per revision. Runtime CI 38073053275 supplied
fourteen assessments and 44 independently verified signatures. Both merged-main
workflows 38104661178 and 38104661177 completed successfully before selection.
The immutable transition receipt is `.flow-agent/evidence/jenkins-loop-baseline.json`,
SHA-256 `c57621fe0cc44fba575474b16727e695eb924945d20102846c9c1a1eb97085c2`.

## Validation boundary

The local Python suite passed all 162 tests. Projection tests cover literal-list
scope, provider absence, native name conflicts and exact renderer bindings.
Protocol tests reject reordered observations, altered occurrence counts, lost
failure origin, malformed records and substituted artifacts. Lifecycle tests
preserve the predecessor chain and finding ownership. Current Kotlin, isolation,
conformance and real runtime results must come from this candidate's GitHub
checks and are reported in the PR after inspection.

All frozen manifests, their pins and historical receipts retain their original
bytes. AR-06 remains active with only A/B formally accepted; AR-07 stays planned,
EF-09 paused and F-20 AR-07-owned. Package and standard versions are unchanged.
