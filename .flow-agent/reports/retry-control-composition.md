# AR-06AE-C4 — Sequential retry control composition

PR #231 merged at `de9e38d9cab7dbcbddc1fe0b60d3c68906b81e64`.
The next correction removes retry eligibility based on a single checkout fixture.
Retry is a control contract: a failed attempt re-enters the complete ordered body,
while normal completion ends repetition immediately. The body may contain any
number of independently materialized tasks, conditions and nested retries.

## Implementation and boundaries

The provider must declare its plan-scoped retry mapping. Every nested policy must
have a positive total attempt limit and fixed zero delay. The same recursive control
boundary is checked again when rendering caller-supplied manifests. Unsupported
leaf payloads and unbound condition values remain blocked by their existing owners.
No task module, action name, alias or body cardinality controls retry eligibility.
Empty bodies complete without inventing a native task invocation.

The topology mapping means re-entry into a new invocation of the complete body. It
does not mean rollback, a fresh workspace or isolation of external effects. A
successful prefix can therefore run again after a later task fails. Lexical result
bindings are established inside each attempt and cannot escape the retry scope.
Approval, loop, match and parallel bodies remain outside this retry mapping until
their replay, cancellation and topology interactions have independent evidence.

## Evidence

Integration tests compile normal Flow Source through the existing frontend and
materialization pipeline. They cover multiple counts and renamed identities,
checkout plus image-build projection, nested policies, empty bodies, unsupported
leaves/providers and forged manifests. Image-build coverage is projection evidence;
its explicit workspace dependency uses the existing parsed-AST compilation boundary.
The corresponding source without that dependency stays blocked. The native
invocation proof uses normal Flow Source and checkout operations only.

An independent Python conformance model reads the original AST and declared fault
budgets. It predicts ordered invocation outcomes without reading adapter guards or
runtime observations. A disposable pinned Jenkins controller executes both Boolean
input assignments and nested exhaustion against an absent immutable revision.
Removing observation stage wrappers recovers each generated artifact byte-for-byte.
Native SCM callbacks identify successful revisions; native FlowNodes record failed
invocations, completed stage boundaries and terminal build results.

Three baselines and six native mutations test omitted prefix/tail work, flattened
outer/inner retries, excessive attempts and repetition after success. Source, AST,
canonical graph, manifest, generated/observed artifacts and logs are content-bound
to the CI revision and run. The proof remains separate from the 39-scenario signed
portfolio. Existing native regressions, exact-head/merge tests and conformance
remain required. CI receipts and final results are recorded in the PR.

Local Python tests and repository checks run here; Java 17 and no local Docker do
not establish the pinned JVM/native result. GitHub CI supplies those executions.
AR-06 stays active, AR-07 planned, EF-09 paused and F-20 AR-07-owned. Next work
addresses remaining loop, match and parallel composition restrictions.
