# AR-A02 candidate report: data-operation payload containment

The predecessor PR #245 merged at
`34a6c84a17bc103eb5eb7387bdbb8c4546c38649`. The next selected audit item is A-02.

## Change

One compiler-owned rule rejects unrepresented transform filters/selections,
aggregate fields and validation rules. Flow validation reports a stable error
without losing AST inspection or other diagnostics. Direct planning throws a
typed exception even when validation is skipped. Empty payload compatibility is
retained without claiming data execution semantics.

The original four audit mutation pairs are rejection vectors. Compiler-only
tests also cover malformed typed rules and field diagnostics. Source integration
tests cover nested control/failure regions, preserved rejected AST payloads and
graph serialization. Intent YAML and reviewed AI retain their existing supported
mapping/schema action parameters and reject source-only fields.

Projection fixtures now explicitly contain empty data payloads; historical
examples and audit evidence are untouched and have rejection regressions.
Legacy planner tests retain their node-kind checks and add payload rejection.
The API-sync reference scenario declares both A-01 and A-02 diagnostics.

## Architecture review

The new guard imports only AST types. Validation and planning share one rule;
there is no extra recursive traversal, generic raw-AST payload, target-specific
semantic branch or runtime execution. No schema version or support claim changes.
Source ownership and conformance inventories include the new files.

## Validation

Current revision CI is pending. The workspace disconnected after source inspection;
no local build or test result is claimed. Required gates are exact-head and
synthetic merge-candidate clean compilation, tests, standalone conformance and
Flow Agent validation, with physical module isolation and existing adapter
runtime checks. Results will be recorded after they run, before release-state
advancement. Final exact-revision evidence belongs in the PR description.

## Limits and next step

This contains silent payload loss; it does not implement typed data-operation
lowering or certify any target. A-02 full support remains open. A-03 global
initializer preservation/rejection is next; A-05 and A-06 remain open.
AR-06 stays active, AR-07 planned, EF-09 paused and F-20 owned by AR-07.
