# AR-06S: Bounded Jenkins Match Certification

## Purpose and boundary

AR-06R preserved match branch identity but supplied no native execution. This
slice consumes that representation in the Jenkins adapter and independently
observes mutually exclusive branch execution. Core and the generic registry are
unchanged. The plan scope admits only string-literal sources, literal boolean
cases with at most one true case, empty error branches and native checkout bodies.
These disjoint predicates avoid inventing precedence or implicit-result semantics.

## Production and behavioral evidence

The renderer keeps authored case order in an if/else-if/else chain. An empty
selected body still suppresses default. Native errors propagate without a catch,
so later body actions and the workflow successor do not run. Unsupported sources,
conditions, nested bodies and nonempty error branches fail closed. The native
catalog is plan scoped and cannot by itself promote generic match support.

Four external Jenkins scenarios execute the exact compiler-rendered Jenkinsfile:
selected, default, empty selected and failing selected branches. Eight mutants
flatten or omit cases, substitute or omit default, force empty-case fallthrough,
omit the successor, or omit/suppress native failure. Native checkout arguments,
counts, distinct step identities, error indices and terminal error origin form the
oracle. Equal final markers and build success cannot hide branch substitution.

The runner pins Jenkins and plugin versions, uses a network-disabled controller
and local Git fixture, authenticates each observation, and binds source, canonical
graph, artifact and implementation digests. The complete portfolio requires the
new producer in the same workflow run alongside every predecessor producer.
Unit tests exercise the protocol using synthetic records; only the dedicated
runtime job supplies real provider behavior evidence.

## Validation and historical baseline

Local 162 Python tests, Flow Agent validation and whitespace checks passed before
updating candidate state. Local Gradle cannot resolve the pinned Kotlin 2.4.10
plugin from the offline cache; no toolchain downgrade or local Kotlin pass is
claimed. Current exact-head, synthetic-merge, isolation, all runtime producers and
portfolio CI are required. Their eventual receipts belong to the PR, not a future
success claim committed into this candidate.

PR #215 merged as e2d3747f06f778b97979c07b56d27aa041e65c4a. Its tree equals both
validated PR revisions. The independently verified predecessor inventory is 2080
tests, 274 ordered conformance checks, 17 assessments and 53 signed observations.
Both actual-main workflows passed. The immutable baseline records those facts
and the selection transition; all older acceptance receipts remain unchanged.

## Limitations and follow-up

No dynamic source, implicit-result binding, overlapping predicates, nonempty error
handler, nested match, branch-output continuity, broader support or portability
claim is made. Empty error wrappers do not provide error-handler execution evidence.
The bounded literal source is inert and introduces no native variable. AR-06
remains active with only A/B formally accepted; AR-07 stays planned, EF-09 paused
and F-20 AR-07-owned. Remaining constructs and cross-target scenarios still need
independent behavior evidence.
