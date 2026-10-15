# AR-06AC — Jenkins condition value binding boundary

PR #225 merged into `cdc449d3c5907adf0aa7713bb021d88479759884`, preserving its independently verified tree. The immutable baseline keeps exact PR receipts and separately observed main workflow status.

## Production correction

Jenkins task leaves do not capture authored result aliases. Previously, condition projection could nevertheless emit those aliases as bare Groovy variables. A canonical declaration or earlier task order does not create a native binding. The generator now traverses parsed condition expressions with the actual lexical scope and keeps missing bindings review-only. It preserves the expression, child steps and identities, removes the unsupported native payload and records `condition.value-binding` with the missing roots. The existing readiness reconciliation prevents an executable artifact.

Declared Flow inputs, secrets and literals remain available. Loop items are available inside their body and workflow error bindings only inside their handler. Neither leaks into sibling steps or another job. Named `matches` patterns remain symbols validated by the existing translator. A direct executable renderer call repeats the binding check, so forged readiness cannot bypass this boundary. An unused result alias is not treated as value-transfer evidence and does not change existing artifacts.

This is an adapter correction consuming unchanged canonical meaning. It does not add a task-output contract, new expression operators, output capture or state transfer. Arbitrary function support and other expression semantics remain governed by their existing boundaries.

## Validation and limits

Local tooling passed all 162 Python tests, Flow Agent validation, context generation and whitespace checks before the candidate state update. Full Kotlin validation requires the pinned JDK 25 / Kotlin 2.4.10 / Gradle 9.5 CI environment; no local toolchain substitution is claimed. Fresh exact-head and synthetic-merge tests, conformance, isolation and every native producer plus authenticated portfolio are required. Final observed receipts belong in the PR, not a preclaimed committed success.

Eight projection regressions cover canonical source, review-only preservation, native inputs, forged executable manifests, nested references and lexical scope. Three lifecycle regressions retain the predecessor evidence chain. The complete 35-assessment, 119-observation portfolio must retain identical source, canonical graph, rendered artifact and observation meaning. No new native scenario is introduced: all 72 publication rows and the explicit value/state evidence gap remain.

AR-06 stays active, AR-07 planned, EF-09 paused and F-20 AR-07-owned. No generic support, portability, public version, historical acceptance or finding closure changes.
