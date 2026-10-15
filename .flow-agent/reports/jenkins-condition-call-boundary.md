# AR-06AD — Jenkins condition function binding boundary

PR #226 merged into `be6875e95cdfa3cbb03ca23b46c87c6ce7b92032`, preserving its independently verified tree. The immutable baseline records exact PR receipts and separately observed main workflow status.

## Production correction

The condition value check introduced in AR-06AC validates variable bindings, but a call with literal or bound arguments has no missing variable. The expression translator previously emitted its function name as a bare Groovy invocation. Canonical acceptance and a syntactically valid identifier cannot establish equivalence with a Jenkins Pipeline step or method. This gap could introduce native effects not represented by the authored action graph.

The Jenkins projection now examines typed expression nodes for calls, including calls nested in arguments, members, indexes, templates, lists, maps and operators. No generic expression function currently has an explicit Jenkins mapping. Affected conditions retain their expression, identity and children in a review-only manifest with `condition.function-binding` and a `nativeFunctionBinding` requirement. Callee names are deduplicated and sorted for stable diagnostics. Declaring an input with the same name does not authorize a function.

The executable renderer repeats the check; direct expression translation also rejects generic calls. The implementation does not execute or resolve the function to decide whether it is safe. Typed secret references remain separate nodes, so `secret("token")` keeps its existing credential binding. Call-like literal text, declared values, supported operators and named patterns retain their existing projection.

This bounded adapter correction precedes output-contract certification. It does not invent the meaning of module outputs, define new canonical functions or promote value/state continuity. A future mapping needs explicit semantic and native behavioral evidence.

## Validation and limits

Local tooling passed 162 Python tests, Flow Agent validation, context generation and whitespace checks before the candidate state update. Full Kotlin validation requires the pinned JDK 25 / Kotlin 2.4.10 / Gradle 9.5 CI environment. Fresh exact-head and synthetic-merge tests, conformance, isolation and every native producer plus authenticated portfolio remain mandatory. Final observed receipts belong in the PR; this report does not preclaim their success.

Eight projection regressions cover accepted canonical source, unsupported calls with bound arguments, nested typed expressions, diagnostic preservation, forged executable manifests, direct translation and preserved secret/literal behavior. Three lifecycle regressions preserve predecessor evidence and finding ownership. The complete 35-assessment, 119-observation portfolio must retain identical source, canonical graph, rendered artifacts and observation meaning; all 72 publication rows remain required.

AR-06 stays active, AR-07 planned, EF-09 paused and F-20 AR-07-owned. No public version, generic support, portability, historical acceptance or finding closure changes.
