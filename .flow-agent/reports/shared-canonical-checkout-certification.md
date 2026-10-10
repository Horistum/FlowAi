# AR-06O candidate: shared canonical native checkout

Both provider experiments compile the same source bytes from
`flow-conformance-kit/src/runtimeTest/shared-checkout.intent.yaml`. The canonical
graphs must match, while each provider retains its own rendered artifact,
implementation identity, runtime prerequisites and observation signatures. Both
execute the baseline, omitted-checkout and substituted-revision cases against
two immutable public repository commits. Independent Git HEAD and fixture-file
SHA-256 observations use one conformance-owned oracle.

The experiment exposed a concrete Jenkins projection defect: full-history SHA
selection used the branch-only `git` shorthand. The renderer now uses native
`checkout scmGit` for full 40-character commit IDs and preserves full history.
Ordinary branch output and explicit shallow-depth handling are unchanged. This
matches the [Jenkins Git step contract](https://www.jenkins.io/doc/pipeline/steps/git/).
No shell projection or Core change is introduced.

A new disposable Jenkins scenario executes the complete generated Jenkinsfile.
It has public Git egress, no published host ports and no supplied credentials;
all legacy local-fixture scenarios keep their disabled network. The observer
reads the finished workspace and actual native checkout count without modifying
the pipeline. The owner signs outside the runtime container. GitHub Actions
retains its checked native-leaf envelope and same-host observer boundary.

The portfolio reauthenticates all twelve assessments and thirty-eight signed
runs before comparing the shared pair. Source/graph digests, exact run inventories
and observation bytes must agree with each other and with the independent
immutable oracle. A coincidentally equal wrong observation cannot pass. The
comparison is explicitly bounded native-checkout observable equivalence; it
neither certifies the full GitHub workflow nor promotes general portability,
structural support, credentials or workspace transfer. All thirty-six matrix
rows and every earlier scenario remain represented.

## Predecessor boundary

PR #211 merged as d38787c3709dcb775b9bd62aa99e1ac5622b2a67 with tree
97e1ff0cf1f3e04758fcb2ba237bbbc0be102bf5, identical to validated PR head
7590fefe3de94ca051586cad2be544580816916f and merge candidate
d5c7f928d506ac5007a2703270676ae918785edb. Flow CI 38055215605 passed
2,024 tests in 348 suites and 274 ordered conformance checks on both revisions.
Runtime CI 38055215602 passed eleven assessments and 35 independently verified
signatures. Main runtime CI 38060038059 passed. Main Flow CI 38060038005 was still
running at selection; this receipt does not claim its result.

The immutable transition receipt is `.flow-agent/evidence/shared-canonical-checkout-baseline.json`,
SHA-256 `631408a6045737a548f124e81049a139162ada5f809bf8d01c2d6a70c29d361e`. Historical acceptance remains unchanged.

## Validation and follow-up

Regressions cover native full-history SHA rendering, identical compiler inputs
and graphs, positive and falsifying mutant observations, failed native execution,
malformed/incomplete inventories, changed source/graph identity and equally wrong
cross-provider outputs. Python tooling and source ownership checks run locally;
current candidate Kotlin suites, physical isolation, standalone conformance and
real provider evidence must pass on exact-head GitHub CI and its merge candidate.
Actual current results are recorded in the PR after validation.

AR-06 remains active; only A/B remain formally accepted. Shared behavioral
coverage beyond checkout and evidence-derived public support gates remain open.
AR-07 stays planned, EF-09 paused and F-20 AR-07-owned. Package and public standard
versions are unchanged.
