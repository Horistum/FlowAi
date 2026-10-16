# AR-06AE — Bounded Jenkins checkout result certification

PR #227 merged into `7220e32a6798f62fd8aee0ca365433f960084c43`, preserving its independently verified tree. The immutable baseline records the exact PR receipts and separately observed main workflow status.

## Output contract

The next AR-06 slice supplies the first bounded value observation on Jenkins. The adapter admits exactly one top-level native `git.checkout -> alias`, followed by a single `if alias.ok == true` or `if alias.ok == false` with one native checkout child in the same job. Both checkouts use literal branches and full history. There are no declared inputs, additional steps, nested bindings or alternate branches in this contract.

The `ok: boolean` field comes from the existing git module output contract. A native `git` call returns normally on success and throws on failure. The renderer introduces the private boolean `flowCheckoutSucceeded = true` immediately after successful completion and renders the direct condition against that binding. It never emits the authored alias as an ambient Groovy variable, invents a whole result record or turns producer failure into `ok = false`.

Projection and direct executable rendering check the same exact shape. Other fields (including `path`), whole records, indexes, safe access, different expressions, arbitrary output transfer, cross-job uses, nesting, extra actions, shallow or immutable-SHA checkouts remain outside this bounded contract. Existing function and value diagnostics continue to fail closed. Core semantics and generic DATA continuity support are unchanged.

## Native evidence

`jenkins-checkout-result-runtime` runs three compiler-bound scenarios, each with its unmodified baseline and two falsifying mutants:

| Scenario | Baseline | Mutants |
| --- | --- | --- |
| True consumer | selected then alternate checkout | omitted and substituted success bindings prevent the consumer |
| False consumer | selected checkout only | substituted binding and bypassed guard run the consumer |
| Failed producer | native missing-revision failure, no consumer | omitted and suppressed failure permit the alternate checkout |

The observer reads native checkout arguments, completion, error origins and workspace markers independently of generated source and mutant identity. It does not inspect Groovy local values. Exact authenticated artifact bytes together with independently observed consumer behavior establish the limited contract; they do not prove generic transfer or mutable state. The runtime pins its Jenkins, Java and plugin prerequisites, including the failure mutant's basic-steps plugin.

The complete portfolio must preserve all 35 predecessor assessments and 119 signatures while adding three assessments and nine signatures. The expected publication is 38 assessments, 128 observations and 72 target/construct rows, of which 14 have bounded evidence. The Jenkins VALUE_STATE_CONTINUITY row carries only these precise limitations. Both public-support and portable-execution flags remain false.

## Validation and limits

Local tooling passed all 162 Python tests, Flow Agent validation, context generation and whitespace checks before the lifecycle update. Five projection regressions cover positive source, unsupported canonical consumers, forged expressions, shape/scope expansion and excluded checkout modes. Five protocol regressions cover artifact binding, restricted matrix publication, tampering, native failure origin and incomplete/unpinned records. Three lifecycle regressions retain historical receipts, required checks and finding ownership.

Fresh exact-head and synthetic-merge Kotlin tests, ordered conformance, module isolation and all native producer/portfolio checks remain required in the pinned JDK 25 / Kotlin 2.4.10 / Gradle 9.5 CI environment. This report does not preclaim their success; observed final receipts belong in the PR.

AR-06 stays active, AR-07 planned, EF-09 paused and F-20 AR-07-owned. No public version, generic support, historical acceptance or finding closure changes.
