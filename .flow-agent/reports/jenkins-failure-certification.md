# AR-06E candidate: native Jenkins failure propagation

The first runtime checkout scenario proves branch selection only. This slice
adds a distinct failure-path proof over two canonical checkout operations with
an explicit dependency and `PROPAGATE` failure disposition. The original
compiler-rendered artifact executes unchanged on the existing disposable Jenkins
controller. The first checkout requests a deliberately nonexistent branch; its
dependent checkout must never execute.

The controller independently records the final result, workspace marker, executed
native-step count and each native step's error. The original must fail with the
exact missing-revision exception and no marker. Omitting the failed operation
or swallowing its error must instead complete successfully with the dependent
checkout's marker. The swallowed error remains visible in the execution graph.

Execution completion and workflow success are different facts. The new scenario
admits only its narrowly specified native failure as completed behavior. An
unexpected exception, abort, incomplete build or infrastructure failure cannot
satisfy its oracle. The original AR-06D scenario still requires successful builds.
No generic admission rule is weakened and no executor enters Core or the CLI.

## Dependency and validation

PR #201 merged into the branch of PR #200 at
`2bfb2d84f907cbaa310debf7f87a5d906f09637f`. PR #200 was still open when this slice
was prepared; main remained `4bea533762e3cff83192c7f9d8ba8244dd3c2598`. This is a
stacked candidate, not an actual-main acceptance of AR-06C, AR-06D or AR-06E.

Eight new protocol tests cover canonical failure/dependency capture, unchanged
positive rendering, deliberate completed failure, both mutants, continued
dependent execution, falsely successful baselines, unexpected runtime errors,
failed or surviving mutants, incomplete builds and malformed/bounded step records.
The existing checkout/authentication tests remain required. Real execution runs
in the dedicated `jenkins-failure-runtime` CI job; synthetic unit records cannot
replace it. Both runtime jobs, full exact-head and merge-candidate tests,
standalone conformance and physical module isolation must pass on the current
revision. Observed results belong in the PR rather than self-attested future
success in committed metadata.

## Limits

This covers native failure propagation to one dependent checkout. It does not
certify general error boundaries, retries or other structural constructs. Public
support views and versions remain unchanged, AR-06 findings remain open and
EF-09 remains paused. Equivalent execution on a second adapter and broader
control/continuity coverage remain subsequent work.
