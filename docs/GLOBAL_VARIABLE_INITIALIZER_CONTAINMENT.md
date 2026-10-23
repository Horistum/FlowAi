# Global variable initializer admission

Audit A-03 found that changing `vars { gate: true }` to `vars { gate: false }`
while retaining `if gate { ... }` produced identical accepted plans, canonical
graphs and digests. The AST and availability analysis retained the declaration;
the execution plan did not represent its initialization. This is compiler
information loss, not evidence of incorrect target execution.

## Current boundary

Every declaration in `flow.vars` now produces the error
`GLOBAL_VARIABLE_INITIALIZER_UNSUPPORTED` during Flow validation. Compilation
stops at `FLOW_VALIDATION` before graph authorization. The diagnostic names the
variable and retains its declaration location when supplied by the parser or AST
caller. The rejected AST still contains the original expressions; unrelated
validation diagnostics remain available.

Existing parser and availability resource limits still apply first. Exhausting
those limits rejects the document with the corresponding limit diagnostic.

Direct `FlowPlanner` callers receive
`UnsupportedGlobalVariableInitializerException`, with `code`, `variableName` and
`sourceLocation`, even if they skip validation. A document with several global
declarations receives one validation error per declaration; direct planning
throws for the first declaration in source order.

This deliberately rejects **all nonempty global declarations**, including unused
variables, declarations shadowed by an input, local assignment or loop binding,
and declarations used only in unreachable or failure regions. Forward references,
backward references, self references and cycles receive the same unsupported
feature diagnostic. The guard does not define their evaluation semantics or
claim to detect cycles. It does not perform constant propagation, substitution or
dead-variable elimination. Even an unused declaration needs a defined evaluation
and lifetime contract before it can safely be discarded.

Parsing remains supported for inspection and migration. An absent or empty
`vars {}` block remains accepted. No AST or execution-plan schema version changes.
The availability analyzer remains an analysis API; its acceptance alone is not
compilation authorization.

## Existing supported forms

Input declarations already survive canonical planning, including supported
Boolean defaults. Ordinary local `set` statements keep their existing planning
behavior. Regressions show that changing these values changes the plan, graph
serialization and digest. They are not automatic replacements for global
initialization: inputs expose caller-supplied values, while local assignments
occur at explicit statement positions and do not establish global failure-handler
scope. Graph admission does not establish adapter executability.

Intent YAML and reviewed AI proposals have no global `vars` declaration in their
Intent model. Their lowering produces an empty `flow.vars` list for each workflow.
The strict Intent loader rejects a source-only `vars` field instead of discarding
it. Multi-workflow YAML and reviewed-AI regressions preserve input defaults and
workflow boundaries without introducing a global-variable side channel.

## Evidence and remaining work

Compiler-only tests exercise typed AST admission without frontends or adapters.
Source integration tests cover the original mutation pair, locations, task
parameters and templates, control/failure regions, shadowing, initialization
order, cycles and independent compilation requests. Three standalone conformance
checks prove source rejection, direct-planner rejection and supported input
preservation.

Historical examples and the original audit are unchanged. The review-only
`deploy-with-approval` projection fixture uses inline expressions without global
declarations; it is explicitly not an equivalent migration of the authored
example. Dynamic-loop adapter tests use a real list input so they continue to
exercise dynamic collection rejection after successful compilation.

A-03 full support remains open. A later implementation must define typed values,
initialization ordering, forward/cyclic reference policy, shadowing and collisions,
workflow/failure-region lifetime, dependency edges, canonical serialization and
mutation-sensitive digests before admitting globals. Adapter support requires its
own evidence. A-06 input/approval value loss is the next bounded audit correction;
A-05 documentation reconciliation remains AR-07-owned.
