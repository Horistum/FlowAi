# AR-06AE-C2 — GitHub condition and successor composition

PR #229 was merged at `abb015000e4372004223a2596a07eecdbf913ba8`, tree
`fe560902cb542d74f3b5156014ae63a815b7a973`. This is the next correction under
active AR-06, preserving all historical acceptance receipts and required gates.

## Problem and change

The GitHub condition resolver admitted exactly one literal guard and one checkout.
Its job-per-task lowering already retained nested guards and explicit dependencies,
but ordinary GitHub success gating suppressed successors of an unselected branch.
Broadening the matcher alone would therefore have silently changed behavior.

The resolver now checks an explicit Boolean expression contract recursively, with
no action-name or node-count gate. Required/defaulted Boolean inputs in manual
contexts, literals, Boolean operators, nested branches and independent leaves
compose. Unsupported values, runtime functions, coercions, non-manual input
contexts and other control structures remain unavailable. Native leaf and
continuity contracts retain their own authority.

Native scheduling accepts a skipped conditional predecessor only if its guard is
false. It checks all prerequisites independently, rejects failure/cancellation,
and preserves ordinary dependency success. The generator records additional
ancestor waits in target-only metadata because GitHub exposes only direct needs
results. The canonical graph is unchanged, independent siblings remain independent,
and direct rendering rejects incomplete ancestor waits. Nested Boolean comparison
operands retain explicit parentheses during expression translation.

## Evidence and boundaries

Unit regressions cover Boolean applicability, ancestor closure/idempotence,
ordinary and conditional dependencies, all failure/cancellation/skip combinations,
missing/cyclic dependencies and incomplete render inputs. Projection tests consume
one canonical AST on both adapters, compare branch selection and preserve all
six authored ordering edges. Additional inputs and another leaf kind do not
determine condition eligibility. Envelope mutations alter guards, native actions
and dependency edges and must fail before native execution.

A reusable GitHub CI workflow executes the compiler-checked native job graph for
both Boolean inputs. It observes each checkout revision, skipped branches, join
and transitive successor, plus two falsifying mutations. The old default-success
guard suppresses the join; reversing the optional guard changes branch execution.
The evidence records the AST, graph, both projections, executed envelope, actual
job results, source/workflow revisions and content hashes. CI runner and job
outputs are trusted. This is not added to the authenticated certification inventory
and carries no signature or public support claim.

Jenkins is compared at projection level here. Native failure/cancellation injection,
cross-runtime composition equivalence, output transport and broader expressions
remain follow-up work. The existing 39 assessments/132 signatures and all earlier
native fixtures remain mandatory regression evidence. AR-06 remains active;
AR-07 is planned, EF-09 paused and F-20 owned by AR-07.

## Validation

Local tooling validation passed 164 Python tests, Flow Agent structure/context
checks and whitespace checks. The local environment has Java 17; the pinned
JDK 25 / Kotlin 2.4.10 / Gradle 9.5.0 validation runs in GitHub CI. Exact-head and
merge-candidate tests, conformance, module isolation, the native composition job
and complete existing portfolio are required. Final observed receipts are recorded
in the PR rather than asserted before execution in this commit.
