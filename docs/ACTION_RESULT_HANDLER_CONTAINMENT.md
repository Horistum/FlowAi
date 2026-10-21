# Action result handler containment (audit A-01)

## Admission contract

Action-local result handlers are still parsed and retained in the AST, but their
rules have no canonical execution-plan representation. Previously the planner
silently dropped those rules, allowing different expectations and error handlers
to authorize identical executable artifacts. This is a compiler correctness defect.

Every action whose `handler.rules` is non-empty now produces the validation error
`ACTION_RESULT_HANDLER_UNSUPPORTED`. The diagnostic identifies `module.action`
and carries the action's source location in `ValidationReport`. The shared
compiler rejects at `FLOW_VALIDATION`, retaining source and AST inspection evidence
without creating a `CompilationUnit` or graph authorization. The public direct
planner independently throws `UnsupportedActionResultHandlerException`, exposing
the same code, action identity and source location.

The rule applies before target selection to all action-local `expect`, conditional
`when` and `when error` rules, including literal true/false predicates, empty rule
bodies and actions inside unselected branches or failure regions. Existing reference,
type and safety diagnostics remain available; this guard does not replace them.

```flow
git.checkout repo {
  url: "https://example.invalid/repo.git"
  branch: "main"
} -> result {
  expect { false }
}
```

This source is rejected. Choosing another adapter or calling `FlowPlanner.plan`
directly cannot make the unsupported assertion disappear.

## Compatibility and migration

- An ordinary result binding (`-> result`) remains supported.
- An empty handler (`-> result {}`) has no rules and remains an explicit no-op with
  the same graph and plan as an ordinary binding.
- A handler containing `expect {}` or `when error {}` still contains a rule and is
  rejected; rule-specific partial support is not inferred from an empty body.
- Standalone conditions such as `if result.ok == true { ... }`, local `try ... on
  error` and workflow-level `on error` keep their existing canonical semantics and
  adapter-specific support gates. This change grants no new target capability.
- Parser and AST inspection remain available. Authored examples containing handler
  rules, including `hello.flow`, `api-sync.flow` and `deploy-with-approval.flow`,
  now fail compilation explicitly. Their source assertions have not been deleted.

There is no automatic migration that removes a handler. Preserve the intended
assertion or recovery behavior when reviewing a rewrite into supported constructs;
result availability, failure propagation and adapter evidence still need validation.
Until such a rewrite is justified, keep the workflow blocked. The public artifact
schemas and package version do not change; this correction tightens acceptance of
previously miscompiled source.

Projection tests use separately named fixtures under
`src/test/resources/flow/projection/`. Those fixtures exercise review-only rendering
contracts without handler assertions; they are not equivalent replacements for the
original examples. The original examples have explicit rejection regressions.

## Evidence and limits

Standalone conformance runs real source compilation, direct planning and an empty
handler positive control. Regression tests additionally cover nested control and
failure regions, typed AST compilation without frontend dependencies, source
locations and native checkout authorization on both existing executable targets.
The relocated CLI proof checks the packaged historical example is rejected with
the same diagnostic and produces no execution plan.

This contains A-01; it does not implement action-handler semantics. Full support
requires typed target-neutral representation, preserved graph identity, failure and
value rules, explicit adapter support and behavioral evidence. Audit A-02 through
A-06 remain open. Availability analysis still precedes validation; its separate
resource-budget finding A-04 is not repaired here.
