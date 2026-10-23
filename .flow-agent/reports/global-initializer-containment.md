# AR-A03 candidate report: global variable initializer containment

PR #246 merged at `73118d2479c72dc2ebd638912f07b5004161db2a`. The next
selected audit item is A-03; this patch follows the audit's explicit-rejection
option because the canonical plan has no global initialization/value contract.

## Change and compatibility

One compiler-owned guard rejects every nonempty `flow.vars` declaration list.
Validation reports `GLOBAL_VARIABLE_INITIALIZER_UNSUPPORTED` with declaration
locations, preserves the AST and continues independent diagnostics. All direct
planner entry points share the same guard and throw a typed exception.

The rule intentionally includes unused or shadowed declarations, forward and
backward references, self references and cycles. It does not infer evaluation
order, lifetime or dead-code semantics. Parsing and empty blocks remain supported.
Inputs and local assignments retain their existing graph behavior without any
new claim about target execution or equivalence to globals.

Seven compiler-only tests and eight source/frontend integration tests cover the
audit pair, typed callers, locations, task parameters/templates, control/failure
regions, declaration order, cycles, shadowing and workflow/request isolation.
YAML and reviewed-AI controls preserve input defaults across two workflows; the
strict Intent loader rejects unknown global fields. Three standalone conformance
checks bind rejection and positive input preservation to the public pipeline.

Historical examples and the audit remain unchanged. The deployment projection
fixture now contains explicit inline expressions, with corresponding parameter
assertions. Its README explains that it is not an equivalent migration of the
original example. The adapter dynamic-loop test uses a list input to keep testing
the adapter boundary after successful compilation. Legacy negative tests retain
handler diagnostics and expect the earlier global-declaration planning rejection
when both unsupported forms are present.

## Architecture and forbidden-direction review

The guard imports only neutral AST types. Validator and planner consume one
admission rule; no additional expression traversal, canonical model, source-hash
workaround or target substitution was introduced. Source ownership and conformance
inventories include the new files. No runtime execution, SDK/plugin lifecycle,
adapter implementation, schema change, signed portfolio expansion or support
promotion is included. The architecture delta checklist passes for this bounded
containment scope.

## Validation

Preliminary local validation on JDK 25 passed production compilation, all seven
new compiler-only tests, all eight new frontend/integration tests, 257 Python tests
and Flow Agent validation. The first frontend run exposed a duplicate step ID in
the new two-workflow test fixture; unique IDs corrected the fixture, and the
subsequent run passed that test without changing production semantics.

The complete local suite and installed conformance run are still in progress at
this report boundary. This is preliminary evidence, not a full-suite success
claim. Final local results and exact-head/synthetic merge-candidate CI evidence
will be recorded in the PR description. Module isolation and existing native
adapter runtime certification remain required.

## Limits and next step

A-03 full variable semantics remain unimplemented. This closes the acceptance hole,
not the design and implementation of initialization order, cycles, shadowing,
workflow/failure lifetime and canonical value dependencies. Existing analysis
resource limits continue to reject oversized inputs before admission diagnostics.

A-06 input/approval value preservation is next. A-02 typed data semantics and A-05
reconciliation remain open. AR-06 stays active, AR-07 planned, EF-09 paused and F-20
AR-07-owned. Final commit-bound CI evidence belongs in the PR description, avoiding
an extra source commit that would invalidate its own reported checks.
