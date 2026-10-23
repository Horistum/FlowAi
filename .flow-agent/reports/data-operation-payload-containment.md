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

Preliminary Flow CI [39872958626](https://github.com/Horistum/FlowAi/actions/runs/39872958626)
verified implementation head `8d3d0c8aad832ef265e95d8f5e38c2284900d323` and
tree `b5de5e1340d7cbd089001b2c4a128b8db9358016`, covering both exact-head
and merge-candidate trees. Its completed job logs record:

- 257 Python tests and Flow Agent validation passed.
- Physical kernel isolation: 19 tests.
- Compiler isolation without frontends/adapters: 64 tests, including all five
  new typed-AST payload regressions.
- Generic adapter isolation: 44 tests.
- Product isolation: 68 tests plus installed-product checks.

These 195 isolated tests are preliminary validation, not the complete suite.
The release state records this verified subset and still requires final-revision
exact-head and synthetic merge-candidate clean builds, full tests and standalone
conformance, plus existing native adapter certification. Final CI evidence is
recorded in PR #246 rather than represented as a result known before that run.

The workspace disconnected after source inspection. No local build or test result
is claimed; validation runs in repository CI.

## Limits and next step

This contains silent payload loss; it does not implement typed data-operation
lowering or certify any target. A-02 full support remains open. A-03 global
initializer preservation/rejection is next; A-05 and A-06 remain open.
AR-06 stays active, AR-07 planned, EF-09 paused and F-20 owned by AR-07.
