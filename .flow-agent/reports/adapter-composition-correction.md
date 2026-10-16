# AR-06AE-C1 — Adapter composition correction

## Why this correction exists

The maintainer identified a real architectural drift after PR #228. Native evidence had repeatedly become a production eligibility rule for the exact fixture shape. Compiler isolation and green runtime jobs did not establish that supported capabilities compose. Flow's universal intent and canonical contracts remain authoritative; an adapter must implement those contracts without making an example workflow the unit of capability.

This correction is based on merged main `0a7ecaa52204c0a73431577af1bce5553d58454f` (PR #228, tree `d6300dd8828663396b8425328a9468c007985902`). It amends the AR-06AE implementation within active AR-06. The historical selected slice, immutable acceptance receipts and validation gates remain intact; this is not another acceptance transition or milestone closure.

## Production change

The checkout-result whole-workflow matcher is removed. One adapter-owned scope analysis walks ordered blocks and resolves exact supported output paths to renderer-owned variables. A binding is introduced only after its native producer completes. Child scopes inherit prior values but do not export their local producers; jobs start with independent scopes. Projection diagnostics and direct rendering use the same analysis, and all condition expressions use the existing AST translator.

The producer contract still defines only `git.checkout.ok` for the existing full-history literal-branch native `git` implementation. Its consumer body no longer has to be a checkout, and its workflow no longer has to contain exactly two steps or zero inputs. Multiple producers receive distinct private names, repeated and nested consumers resolve their actual producer, and authored loop/handler names cannot capture generated variables. Unused result aliases generate no new binding.

Core, canonical meaning, public DSL, manifest schema and generic continuity declarations are unchanged. This intentionally remains an adapter correction: the defect was in an adapter, and creating a second universal value model would introduce another authority rather than fix it.

## Remaining composition audit

These are implementation/evidence limits observed in the current sources, not claims about inherent vendor-platform limitations.

| Area and source | Current production restriction | Required architectural follow-up |
| --- | --- | --- |
| Value conditions, `JenkinsProjectionRenderingSupport.kt` | Exact supported native field and lexical availability; no exported branch/job state | This PR removes workflow-size and consumer-shape gates. Additional fields need concrete contracts, not new whole-workflow matchers. |
| Conditions, `GitHubLiteralConditionProjectionScope.kt` | One literal guard, one checkout, no inputs, nesting or successors | Next priority: composable condition mapping and explicit job scheduling/dependency semantics, compared with the same canonical intent on another adapter. |
| Retry, `JenkinsRetryProjectionScope.kt` | One checkout body, positive fixed zero-delay policy | Replace body type/count gates with control and attempt/state contracts. Preserve failure and workspace semantics. |
| Loops, `JenkinsLoopProjectionScope.kt` | Literal string list and checkout/condition bodies; no nested loops | Separate iteration expression support, body bindings and lexical state. Prove supported nesting and empty/failing iterations. |
| Match, `JenkinsMatchProjectionScope.kt` | Literal source/guards, at most one true case, checkout bodies, empty error branch | Implement expression, branch-selection and error contracts independently of the action chosen for the fixture. |
| Parallel, `JenkinsParallelProjectionScope.kt` and renderer | One group, two approval branches, optional checkout, prescribed workspace shape | Derive branch applicability from topology, cancellation, join and continuity contracts; test varying branch count and supported bodies. |

The roadmap now prioritizes these reusable mappings and cross-target composition. More fixtures may be needed as evidence, but cannot themselves define production applicability. Existing semantic-loss repairs, fail-closed checks and native receipts remain valuable and are retained.

## Verification

Projection regressions exercise added inputs and independent prefix/suffix steps, existing boolean expressions, multiple producers, repeated/nested consumers, a manual approval mixed with checkout children, generated-name hygiene, unsupported fields, forward references, sibling/branch escape, duplicate aliases and cross-job use. Unsupported producer checkout variants still fail closed; unrelated consumer checkout variants retain their own contract.

The original 38 assessments and 128 signed observations remain required. The new composition fixture adds one assessment and four native runs: baseline, omitted first binding, substituted second binding and omitted inner binding. Independent native checkout order and workspace markers distinguish every mutant from the baseline. The expected portfolio contains 39 assessments and 132 signatures, with the same 14 bounded rows and 58 evidence gaps across six targets and twelve constructs. Counts describe an inventory, not architectural completion.

All 162 Python tooling tests and Flow Agent structure/context checks passed locally. Kotlin compilation, exact-head and synthetic-merge tests, ordered conformance, module isolation and the full native portfolio require the pinned JDK 25 / Kotlin 2.4.10 / Gradle 9.5 CI environment; local Java is 17. Final observed CI receipts belong in the PR and are not preclaimed here.

No generic support or portable execution is promoted. AR-06 remains active, AR-07 planned, EF-09 paused and F-20 owned by AR-07.
